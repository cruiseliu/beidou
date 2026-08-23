package org.gms.scripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * GraalJS ES module 加载工具（单例缓存）。
 *
 * 对应 ESM 语义：
 *   <pre>
 *   JsModule m = JsModule.importModule("lib/add.js");   // 相当于 import * as m from "scripts/lib/add.js"
 *   Object  add = m.get("add");                         // 相当于 import { add } from ...
 *   Object  def = m.getDefault();                       // 相当于 import default from ...
 *   Map     all = m.getAll();                           // 相当于 import * from ...
 *   Object  r   = m.call("add", 1, 2);                  // 取导出函数并调用
 *   m.destroy();                                        // 释放引擎（模拟卸载模块）
 *   </pre>
 *
 * <h3>泛型返回</h3>
 * {@link #get(String)} 与 {@link #call(String, Object...)} 返回类型由泛型决定：
 * 不写泛型参数（默认 Object）返回原始 polyglot {@link Value}（可继续 execute/getMember）；
 * 指定具体类型则按该类型转换，如 {@code String s = m.get("name")}。
 * 标量返回 Java 原生类型；JS 里绑定的 Java 对象（host object）还原为原 Java 对象。
 *
 * <h3>单例语义</h3>
 * 与 JS 引擎一致：多次 {@link #importModule(String)} 同一路径返回同一个实例（模块只初始化一次）。
 * 路径按规范化后的相对 scripts/ 路径作为缓存键，"lib/add.js" 与 "./lib/add.js" 命中同一实例。
 * {@link #destroy()} 把该路径从缓存移除并关闭引擎，此后再次 import 同路径会创建全新实例（干净上下文）。
 *
 * <h3>线程安全</h3>
 * 缓存 map 由 {@link ReentrantReadWriteLock} 保护（读多写少：import 命中走读锁，destroy 走写锁）。
 * 实际访问（get/call 等）用实例锁 synchronized 保护——polyglot Context 不支持多线程并发
 * （实测多线程并发 execute 抛 "Multi threaded access requested but is not allowed for language(s) js"），
 * 同一模块的所有调用串行执行；模块内为纯计算，串行无副作用问题。
 */
public final class JsModule implements AutoCloseable {

    private static final String SCRIPT_DIRECTORY = "scripts";
    private static final String MODULE_MIME_TYPE = "application/javascript+module";

    /** 共享底层 GraalVM Engine（只共享编译产物与解析器，模块状态仍在各自 Context 内隔离） */
    private static final Engine SHARED_ENGINE = Engine.create();

    /** 路径 → 模块实例 缓存（键为规范化相对路径，如 "lib/add.js"） */
    private static final Map<String, JsModule> CACHE = new HashMap<>();
    private static final ReentrantReadWriteLock CACHE_LOCK = new ReentrantReadWriteLock();

    private final String key;
    private final GraalJSScriptEngine engine;
    private final Value exports;
    private boolean destroyed;

    private JsModule(String key) throws IOException {
        this.key = key;
        this.engine = GraalJSScriptEngine.create(SHARED_ENGINE, Context.newBuilder("js")
                .option("js.ecmascript-version", "2022")
                // eval 直接返回模块的 exports 命名空间（默认是 undefined）
                .option("js.esm-eval-returns-exports", "true")
                // 允许相对 import 读取磁盘上的依赖文件
                .allowIO(true)
                .allowAllAccess(true));
        // File 重载 + module MIME：相对 import（./lib/xxx.js）按入口文件所在目录解析
        this.exports = engine.getPolyglotContext().eval(
                Source.newBuilder("js", Path.of(SCRIPT_DIRECTORY, key).toFile())
                        .mimeType(MODULE_MIME_TYPE)
                        .build());
    }

    /**
     * 加载（或取缓存）scripts/ 下的 ES module。
     *
     * @param path "lib/add.js" 或 "./lib/add.js"，均解析为 $PWD/scripts/lib/add.js；同一路径返回同一实例
     * @return JsModule 实例
     * @throws IllegalArgumentException 文件不存在或路径越出 scripts/ 目录
     */
    public static JsModule importModule(String path) {
        String key = normalizeKey(path);

        // 先读锁查缓存（命中即返回，不竞争写锁）
        CACHE_LOCK.readLock().lock();
        try {
            JsModule cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        } finally {
            CACHE_LOCK.readLock().unlock();
        }

        // 未命中：升级写锁 double-check 后创建
        CACHE_LOCK.writeLock().lock();
        try {
            JsModule cached = CACHE.get(key);
            if (cached == null) {
                try {
                    cached = new JsModule(key);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to load JS module: " + key, e);
                }
                CACHE.put(key, cached);
            }
            return cached;
        } finally {
            CACHE_LOCK.writeLock().unlock();
        }
    }

    /**
     * 获取命名导出（import { name } from path）。
     *
     * <p>返回类型由泛型决定：
     * <ul>
     *   <li>不写泛型参数（默认 Object）→ 返回原始 polyglot {@link Value}，可继续 execute/getMember；
     *   <li>指定具体类型 → 按该类型转换（如 {@code String s = m.get("name")}、{@code Value f = m.get("fn")}）。
     * </ul>
     * 导出不存在时返回 null。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        synchronized (this) {
            ensureAlive();
            return (T) toJava(exports.getMember(name));
        }
    }

    /** 获取默认导出（import default from path）。返回 null 表示没有默认导出。 */
    public Object getDefault() {
        synchronized (this) {
            ensureAlive();
            return toJava(exports.getMember("default"));
        }
    }

    /** 获取全部导出（import * from path），键为导出名。 */
    public Map<String, Object> getAll() {
        synchronized (this) {
            ensureAlive();
            Map<String, Object> all = new LinkedHashMap<>();
            for (String name : exports.getMemberKeys()) {
                all.put(name, toJava(exports.getMember(name)));
            }
            return all;
        }
    }

    /**
     * 调用命名导出函数并返回其结果（函数参数按顺序透传给 JS）。
     * 返回类型由泛型决定：不写泛型参数返回原始 {@link Value}，指定具体类型则按该类型转换。
     */
    @SuppressWarnings("unchecked")
    public <T> T call(String functionName, Object... args) {
        synchronized (this) {
            ensureAlive();
            Value fn = exports.getMember(functionName);
            if (fn == null || !fn.canExecute()) {
                throw new IllegalArgumentException("JS module has no callable export: " + functionName);
            }
            return (T) toJava(fn.execute(args == null ? new Object[0] : args));
        }
    }

    /**
     * 卸载本模块：从缓存移除并释放脚本引擎，此后再次 import 同路径会创建全新实例。
     * 调用后本实例所有 get/call 抛 IllegalStateException。
     */
    public void destroy() {
        synchronized (this) {
            if (destroyed) {
                return;
            }
            destroyed = true;
            engine.close();
        }
        CACHE_LOCK.writeLock().lock();
        try {
            CACHE.remove(key, this);
        } finally {
            CACHE_LOCK.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        destroy();
    }

    /** 规范化缓存键：统一为相对 scripts/ 的路径（"lib/add.js"），并做越界校验。 */
    private static String normalizeKey(String path) {
        Path base = Path.of(SCRIPT_DIRECTORY);
        Path resolved = base.resolve(path).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("JS module path escapes scripts/ dir: " + path);
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("JS module not found: " + resolved.toAbsolutePath());
        }
        return base.relativize(resolved).toString().replace('\\', '/');
    }

    private void ensureAlive() {
        if (destroyed) {
            throw new IllegalStateException("JsModule already destroyed");
        }
    }

    /**
     * 把 polyglot Value 转成 Java 友好类型：
     * <ul>
     *   <li>null/undefined → null；
     *   <li>Java 对象绑定（host object，如 JS 里通过 Java.type/传入的 Java 对象）→ 还原为原 Java 对象；
     *   <li>标量（string/boolean/number）→ Java 原生类型（String/Boolean/int/long/double）；
     *   <li>函数、对象、数组等 → 保留 polyglot {@link Value}，可继续 execute/getMember。
     * </ul>
     */
    private static Object toJava(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        // Java 对象绑定：JS 值直接是宿主对象（如 new (Java.type("java.util.ArrayList"))()），还原原对象
        if (v.isHostObject()) {
            return v.asHostObject();
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            if (v.fitsInInt()) {
                return v.asInt();
            }
            if (v.fitsInLong()) {
                return v.asLong();
            }
            return v.asDouble();
        }
        // 函数、对象、数组等保留 polyglot Value，可继续 execute/getMember
        return v;
    }
}
