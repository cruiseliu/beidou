package org.gms.scripting;

import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JS 定时器引擎 + 脚本会话（scripts/lib/bind.js 的 setTimeout/clearTimeout Java 背书，见 doc/10）。
 *
 * 引擎契约（与脚本侧约定一致）：
 * <ul>
 *   <li>上下文常驻：待触发定时器经 Entry 强引用闭包 Value，闭包可达即脚本引擎上下文不释放；</li>
 *   <li>无并行：同一 {@link Session} 的所有脚本执行（定时器回调、容器事件派发）互斥串行，
 *       脚本按单线程心智书写；不同 Session 之间由 {@link JsModule} 实例锁兜底——同一 context
 *       的所有进入路径共用该监视器，polyglot Context 禁止并发进入；</li>
 *   <li>脚本内可再调 setTimeout：执行期间会话经 {@code CURRENT_SESSION}、所属模块经
 *       {@link JsModule#current()} 可查，登记的定时器归属同一会话与 context；</li>
 *   <li>会话关闭：close 后 run 拒绝进入脚本，待触发定时器到点静默丢弃；</li>
 *   <li>clearTimeout 对未知/已触发 id 静默；</li>
 *   <li>任务异常记日志吞掉，调度线程与会话存活（fail-safe）。</li>
 * </ul>
 */
public final class ScriptTimers {

    private static final Logger log = LoggerFactory.getLogger(ScriptTimers.class);

    private static final AtomicLong IDS = new AtomicLong();
    /** id → 待触发定时器（触发/取消即出表；Entry 持闭包 = 持上下文） */
    private static final Map<Long, Entry> TIMERS = new ConcurrentHashMap<>();
    /** 当前线程正在执行的会话（脚本内嵌套 setTimeout 归属判定） */
    private static final ThreadLocal<Session> CURRENT_SESSION = new ThreadLocal<>();
    /** 触发调度（单线程守护池；到点后进会话锁串行执行，池本身不承载脚本逻辑） */
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "script-timer");
        t.setDaemon(true);
        return t;
    });

    private ScriptTimers() {
    }

    /** 新建脚本会话（由 CharacterScriptRunner 持有；测试/工具亦可独立使用） */
    public static Session openSession() {
        return new Session();
    }

    /** 登记定时器：delayMs 后在发起调用的会话内执行 fn。必须在脚本会话执行期间调用（嵌套或 hooks 内）。 */
    public static long setTimeout(Value fn, long delayMs) {
        Session session = CURRENT_SESSION.get();
        JsModule module = JsModule.current();   // 触发时经同一模块锁回到本 context
        if (session == null || module == null) {
            throw new IllegalStateException("setTimeout called outside a script session");
        }
        long id = IDS.incrementAndGet();
        ScheduledFuture<?> future = SCHEDULER.schedule(() -> fire(id), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        TIMERS.put(id, new Entry(session, module, fn, future));
        return id;
    }

    /** 取消定时器；未知/已触发 id 静默 */
    public static void clearTimeout(long id) {
        Entry entry = TIMERS.remove(id);
        if (entry != null) {
            entry.future.cancel(false);
        }
    }

    private static void fire(long id) {
        Entry entry = TIMERS.remove(id);
        if (entry != null) {
            entry.session.run(() -> entry.module.execute(entry.fn));
        }
    }

    /**
     * 脚本会话：一组串行执行域（定时器回调与容器事件共用；由 CharacterScriptRunner 持有）。
     * 会话内执行的脚本通过 {@code CURRENT_SESSION} 发现自己，bind 层的定时器登记随之归属本会话。
     */
    public static final class Session {

        private final Object lock = new Object();
        private boolean closed;

        Session() {
        }

        /** 在本会话锁内执行任务；已关闭的会话静默跳过。任务内可嵌套 setTimeout。 */
        public void run(Runnable task) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                CURRENT_SESSION.set(this);
                try {
                    task.run();
                } catch (RuntimeException e) {   // 含 PolyglotException（脚本内异常），fail-safe：会话存活
                    log.error("脚本执行异常（会话存活）", e);
                } finally {
                    CURRENT_SESSION.set(null);
                }
            }
        }

        /** 关闭会话：此后不再进入脚本；待触发定时器到点静默丢弃 */
        public void close() {
            synchronized (lock) {
                closed = true;
            }
        }
    }

    private static final class Entry {
        final Session session;
        final JsModule module;
        final Value fn;
        final ScheduledFuture<?> future;

        Entry(Session session, JsModule module, Value fn, ScheduledFuture<?> future) {
            this.session = session;
            this.module = module;
            this.fn = fn;
            this.future = future;
        }
    }
}
