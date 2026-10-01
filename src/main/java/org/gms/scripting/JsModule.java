package org.gms.scripting;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ESM 模块句柄（per-client，M1.5）：一个模块文件在一个 client context 内的装载结果。
 * Context 归各角色脚本宿主（CharacterScriptRunner）所有——模块注册表/单例缓存/互斥锁
 * 均已上移：同 context 的进入串行由 strand 单线程构造性保证，本类只负责取导出与执行。
 *
 * <p>对应 ESM 语义：
 * <pre>
 * JsModule m = runner.moduleFor("item/pet.js");  // import * as m from "actorscripts/item/pet.js"
 * Object fn  = m.get("onEnterInventory");        // import { onEnterInventory } from ...
 * Object r   = m.call("add", 1, 2);              // 取导出函数并调用
 * </pre>
 *
 * <p><b>线程模型</b>：所有方法必须在 owning strand 上调用（moduleFor 经宿主 run/call
 * 保证）；无锁——polyglot Context 的并发禁入由 strand 串行满足。
 *
 * <p><b>状态作用域变化（M1.5）</b>：模块级状态（全局变量）作用域从"全服共享"收敛为
 * "per-client"——依赖跨角色共享模块状态的脚本（如 coupon.js 的 pendingTimers）在
 * 多客户端版本需要迁 Java 侧共享存储。
 */
public final class JsModule {

    /** ESM 脚本根目录（新脚本系统；legacy js 系统仍在 scripts/，互不混用） */
    private static final String SCRIPT_DIRECTORY = "actorscripts";
    private static final String MODULE_MIME_TYPE = "application/javascript+module";

    private final String key;
    private final Value exports;
    private boolean destroyed;

    public JsModule(Context context, String key) throws IOException {
        this.key = key;
        // File 重载 + module MIME：相对 import（./lib/xxx.js）按入口文件所在目录解析
        this.exports = context.eval(
                Source.newBuilder("js", Path.of(SCRIPT_DIRECTORY, key).toFile())
                        .mimeType(MODULE_MIME_TYPE)
                        .build());
    }

    /** 规范化缓存键：统一为相对 actorscripts/ 的路径（"lib/add.js"），并做越界校验。 */
    public static String normalizeKey(String path) {
        Path base = Path.of(SCRIPT_DIRECTORY);
        Path resolved = base.resolve(path).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("JS module path escapes actorscripts/ dir: " + path);
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("JS module not found: " + resolved.toAbsolutePath());
        }
        return base.relativize(resolved).toString().replace('\\', '/');
    }

    /**
     * 获取命名导出（import { name } from path）。返回类型由泛型决定：
     * 不写泛型返回原始 polyglot {@link Value}；指定类型则转换。导出不存在返回 null。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        ensureAlive();
        return (T) toJava(exports.getMember(name));
    }

    /** 获取默认导出（import default from path）。返回 null 表示没有默认导出。 */
    public Object getDefault() {
        ensureAlive();
        return toJava(exports.getMember("default"));
    }

    /** 获取全部导出（import * from path），键为导出名。 */
    public Map<String, Object> getAll() {
        ensureAlive();
        Map<String, Object> all = new LinkedHashMap<>();
        for (String name : exports.getMemberKeys()) {
            all.put(name, toJava(exports.getMember(name)));
        }
        return all;
    }

    /** 调用命名导出函数并返回其结果。返回类型由泛型决定（同 {@link #get}）。 */
    @SuppressWarnings("unchecked")
    public <T> T call(String functionName, Object... args) {
        ensureAlive();
        Value fn = exports.getMember(functionName);
        if (fn == null || !fn.canExecute()) {
            throw new IllegalArgumentException("JS module has no callable export: " + functionName);
        }
        return (T) toJava(fn.execute(args == null ? new Object[0] : args));
    }

    /** 执行持有的 polyglot 闭包（定时器回调等"持有 Value 延迟执行"的路径共用）。 */
    public Object execute(Value fn, Object... args) {
        ensureAlive();
        return fn.execute(args == null ? new Object[0] : args);
    }

    /**
     * 调用具名导出（取导出、可执行检查、执行一气呵成）；导出不存在/不可执行返回 null。
     * 返回值经 {@link #toJava} 转换，与 {@link #call} 一致。
     */
    public Object callExport(String name, Object... args) {
        ensureAlive();
        Value fn = exports.getMember(name);
        if (fn == null || !fn.canExecute()) {
            return null;
        }
        return toJava(fn.execute(args == null ? new Object[0] : args));
    }

    /** 标记失效（context 归宿主所有，关闭由宿主负责；本方法只阻断后续访问） */
    void destroy() {
        destroyed = true;
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
     *   <li>Java 对象绑定（host object）→ 还原为原 Java 对象；
     *   <li>标量（string/boolean/number）→ Java 原生类型；
     *   <li>函数、对象、数组等 → 保留 polyglot {@link Value}。
     * </ul>
     */
    private static Object toJava(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
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
        return v;
    }
}
