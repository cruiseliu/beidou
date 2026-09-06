package org.gms.client.character;

import org.gms.infra.DeadlineTimer;
import org.gms.infra.Strand;
import org.gms.scripting.JsModule;
import org.gms.scripting.ScriptTimers;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 角色脚本宿主（per-client ESM，M1.5）：一个 polyglot Context + 一张模块注册表 +
 * 一个脚本定时器容器，全部脚本执行收敛在 owner 的 strand 上——polyglot Context
 * 禁止并发进入的限制由 strand 单线程构造性满足（原 JsModule 实例锁/脚本会话锁退役）。
 *
 * <p><b>同步契约</b>：所有入口语义同步——JS 函数返回后调用方才继续（strand.run/supply：
 * strand 内 inline，跨线程调用入队等待）。入包/出包钩子同为同步：它们是状态迁移的后半段
 * （道具↔宠物等耦合在此建立/拆除），调用方不得看到半Applied 状态；InventoryTransaction
 * 在 end() 释放背包锁之后才派发钩子，避免"持背包锁等 strand"的死锁配方。
 *
 * <p>脚本内的异步诉求经 setTimeout（bind.js）表达：回调到点 post 回本 strand 串行执行，
 * 执行期间 {@link ScriptTimers} 的当前宿主指向本实例（嵌套 setTimeout 归属判定）。
 * {@link #close}（登出）后定时器静默丢弃、脚本入口跳过——整个 context 随之消亡，
 * 未及执行的清理钩子无碍。
 */
public class CharacterScriptRunner implements ScriptTimers.Host {
    private static final Logger log = LoggerFactory.getLogger(CharacterScriptRunner.class);

    /** 共享底层 Engine（只共享编译产物与解析器；隔离边界是各 client 的 Context） */
    private static final Engine SHARED_ENGINE = Engine.create();

    private final Supplier<Strand> strandSupplier;
    /** 模块注册表（path → 模块句柄）；仅 strand 上访问 */
    private final Map<String, JsModule> modules = new HashMap<>();
    /** 待触发脚本定时器（id → 句柄）；strand 上读写，close 在登出线程 → 并发容器 */
    private final Map<Long, DeadlineTimer.TimerHandle> timers = new ConcurrentHashMap<>();
    private final AtomicLong timerIds = new AtomicLong();
    private volatile Context context;
    private volatile boolean closed;

    public CharacterScriptRunner(Supplier<Strand> strandSupplier) {
        this.strandSupplier = strandSupplier;
    }

    // ── 执行入口（全部同步语义） ──

    /**
     * 同步执行任务：strand 内 inline、跨线程入队等待。closed/strand 已关闭 → 静默跳过。
     * 任务异常吞掉记日志（fail-safe，宿主存活）。
     */
    public void run(Runnable task) {
        runResult(task);
    }

    /**
     * 同步执行并取回结果（调用方需要脚本结论时使用，如道具 onUse、AP 分配）。
     * 任务异常/已关闭 → null（fail-safe）。
     */
    public <T> T call(Supplier<T> task) {
        AtomicReference<T> out = new AtomicReference<>();
        runResult(() -> out.set(task.get()));
        return out.get();
    }

    private void runResult(Runnable task) {
        if (closed) {
            return;
        }
        Strand s = strandSupplier.get();
        if (s == null) {
            executeScript(task);   // 无连接（mock/测试）：原地执行
            return;
        }
        try {
            s.run("script", () -> executeScript(task));
        } catch (IllegalStateException e) {
            log.debug("strand 已关闭，脚本任务跳过", e);   // 登出竞态：静默
        }
    }

    /** strand 上执行脚本任务：吞异常（fail-safe）；期间登记脚本定时器宿主（嵌套 setTimeout 归属） */
    private void executeScript(Runnable task) {
        if (closed) {
            return;
        }
        ScriptTimers.Host prev = ScriptTimers.currentHost();
        ScriptTimers.currentHost(this);
        try {
            task.run();
        } catch (RuntimeException e) {   // 含 PolyglotException（脚本内异常）
            log.error("脚本执行异常（宿主存活）", e);
        } finally {
            ScriptTimers.currentHost(prev);
        }
    }

    // ── 模块 ──

    /**
     * per-client 模块解析：同路径惰性 eval 一次后复用（ESM 单例语义，作用域为本 client）。
     * 文件缺失抛 UncheckedIOException。必须经由 run/call 在 strand 上消费。
     */
    public JsModule moduleFor(String path) {
        return call(() -> modules.computeIfAbsent(JsModule.normalizeKey(path), p -> {
            try {
                return new JsModule(context(), p);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load JS module: " + p, e);
            }
        }));
    }

    private Context context() {
        Context c = context;
        if (c == null) {
            c = Context.newBuilder("js")
                    .engine(SHARED_ENGINE)
                    .option("js.ecmascript-version", "2022")
                    // eval 直接返回模块的 exports 命名空间（默认是 undefined）
                    .option("js.esm-eval-returns-exports", "true")
                    // 允许相对 import 读取磁盘上的依赖文件
                    .allowIO(true)
                    .allowAllAccess(true)
                    .build();
            context = c;
        }
        return c;
    }

    // ── 脚本定时器（ScriptTimers.Host；bind.js 的 setTimeout/clearTimeout 背书） ──

    @Override
    public long setTimeout(Value fn, long delayMs) {
        long id = timerIds.incrementAndGet();
        long deadline = DeadlineTimer.now() + Math.max(0, delayMs);
        var handle = DeadlineTimer.schedule(strandSupplier.get(), deadline, "script-timer#" + id,
                scheduledMs -> {
                    timers.remove(id);
                    executeScript(() -> fn.execute());
                });
        timers.put(id, handle);
        return id;
    }

    @Override
    public void clearTimeout(long id) {
        var handle = timers.remove(id);
        if (handle != null) {
            handle.cancel();
        }
    }

    /** 热重载预留：清空模块注册表，此后 moduleFor 重新 eval（context 不重建） */
    public void reload() {
        run(modules::clear);
    }

    /** 登出清场：取消全部待触发定时器并关闭 context；此后入口静默跳过 */
    public void close() {
        closed = true;
        timers.forEach((id, handle) -> handle.cancel());
        timers.clear();
        Context c = context;
        if (c != null) {
            try {
                c.close();   // 可能与 strand 上尚在执行的脚本竞态：PolyglotException 由 executeScript 吞掉
            } catch (Throwable t) {
                log.debug("脚本 context 关闭异常（登出竞态容忍）", t);
            }
        }
    }
}
