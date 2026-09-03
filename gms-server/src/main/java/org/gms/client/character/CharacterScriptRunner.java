package org.gms.client.character;

import org.gms.scripting.ScriptTimers;
import org.gms.server.ThreadManager;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 角色脚本串行执行域：同一角色的脚本任务按提交序、单线程语义执行。
 *
 * <ul>
 *   <li>事件序：任务经串行队列严格按提交序执行（快速"加又删"不乱序）；</li>
 *   <li>会话绑定：执行在 {@link ScriptTimers.Session#run} 内——脚本内 setTimeout 归属
 *       本会话，跨任务并发由会话锁互斥（"context 内无并行"契约）；</li>
 *   <li>异步：入队零成本，排空投递到 {@link ThreadManager} 共享池（串行化由 draining 标志
 *       保证，池多线程不破坏序）；</li>
 *   <li>{@link #close()}：排空余量后关闭会话（登出），此后提交的任务静默丢弃。</li>
 * </ul>
 *
 * 不理解任务内容——任务由调用方（ItemScript）组装。
 */
public class CharacterScriptRunner {

    private final ScriptTimers.Session session = ScriptTimers.openSession();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile boolean closed;

    /** 串行提交一个脚本任务 */
    public void post(Runnable task) {
        if (closed) {
            return;
        }
        tasks.add(task);
        scheduleDrain();
    }

    /**
     * 会话内同步执行任务并取回结果（调用方需要脚本结论时使用，如道具 onUse 钩子）。
     * 与队列任务经会话锁互斥，但不经队列（无 FIFO 保证）；任务异常由会话吞掉，
     * 返回 null（fail-safe）。会话已关闭时同样返回 null。
     */
    public <T> T call(Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        session.run(() -> result.set(task.get()));
        return result.get();
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            ThreadManager.getInstance().newTask(this::drainLoop);
        }
    }

    private void drainLoop() {
        try {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                session.run(task);
            }
        } finally {
            draining.set(false);
        }
        if (!tasks.isEmpty()) {
            scheduleDrain();   // 排空与入队竞态窗口期间落队的任务
        }
    }

    /** 排空余量后关闭会话（登出）；此后提交的任务静默丢弃 */
    public void close() {
        drainLoop();
        session.close();
        closed = true;
    }
}
