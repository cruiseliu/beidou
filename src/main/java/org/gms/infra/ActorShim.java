package org.gms.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 伪 actor（shim）：actor 形状的调用边界——与 {@link Strand} 同款的 FIFO 队列与
 * post/run/supply API。任务由<b>单个常驻 drain 虚拟线程严格串行</b>执行（doc/13 §4 修订）：
 * map actor 的终态语义（串行）即 shim 的语义——同 shim 内任务按入队序完成，无并发重叠。
 *
 * <p><b>语义契约</b>：
 * <ul>
 *   <li><b>串行</b>：单 worker 按入队序逐个执行；任务内阻塞（发包/DB）卸载载体线程，
 *       但阻塞本 shim 后续任务（串行语义）。</li>
 *   <li><b>run/supply</b>：排序敏感缝合点——入队并阻塞等待完成；CURRENT==this 时 inline
 *       （重入语义同 Strand）。等待方不得持有任务体可能需要的锁。</li>
 *   <li><b>纪律</b>：任务体内禁止 run/supply 回 player strand（跨 actor 互等 = 环死锁；
 *       含脚本入口的地图方法不得进任务体）；回 player 只许 post（fire-and-forget）。</li>
 *   <li><b>无生命周期</b>：不设 close/finalizeWith——队列与 shim 随宿主对象回收；
 *       drain 为 daemon，随 JVM 退出。</li>
 * </ul>
 */
public final class ActorShim {
    private static final Logger log = LoggerFactory.getLogger(ActorShim.class);

    private static final long SLOW_TASK_WARN_MS = 5_000;

    private static final AtomicLong SEQ = new AtomicLong();
    private static final ThreadLocal<ActorShim> CURRENT = new ThreadLocal<>();

    private final String name;
    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    /** worker 惰性启动（首个任务入队时），常驻直至 JVM 退出 */
    private boolean started = false;

    /** @param ctx strict 管线因果上下文快照（post 捕获；null = 无窗流量零包装） */
    private record Task(String name, Runnable body, PipelineContext ctx) {
    }

    private final PipelineContext.Owner domain;

    private ActorShim(String name) {
        this(name, null);
    }

    private ActorShim(String name, PipelineContext.Owner domain) {
        this.name = name;
        this.domain = domain;
    }

    /** 创建 shim；name 用于日志/线程名关联（自动追加序号） */
    public static ActorShim create(String name) {
        return new ActorShim(name + "-" + SEQ.incrementAndGet());
    }

    /** 带执行域 owner 的 shim（map actor = 其宿主地图）：任务体 establish 时盖章 */
    public static ActorShim create(String name, PipelineContext.Owner domain) {
        return new ActorShim(name + "-" + SEQ.incrementAndGet(), domain);
    }

    /** 当前线程正在执行的本 shim 任务；不在任何 shim 任务内返回 null */
    public static ActorShim current() {
        return CURRENT.get();
    }

    /** 入队即返（通知类调用）；无拒绝场景（无界队列，对齐 Strand） */
    public void post(String taskName, Runnable body) {
        enqueue(new Task(taskName, body, PipelineContext.current()));
    }

    /**
     * 入队并阻塞等待完成（排序敏感缝合点：调用返回后本线程还要发自己的包或依赖其效果时用）。
     * 已在本 shim 任务体内则 inline（等价同步调用）。
     */
    public void run(String taskName, Runnable body) {
        supply(taskName, () -> {
            body.run();
            return null;
        });
    }

    /**
     * {@link #run} 带返回值：时序交接的产物（如登记动作回报的 firstEnter）。
     * <b>不服务读导航</b>——"读地图状态给自己做决策"走调用方直读（宿主锁保护），
     * "上一步地图动作的产物"才走本方法（doc/13 §2）。
     */
    public <T> T supply(String taskName, Supplier<T> task) {
        if (onShim()) {
            return task.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        enqueue(new Task(taskName, () -> {
            try {
                result.set(task.get());
            } finally {
                done.countDown();
            }
        }, PipelineContext.current()));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 shim 任务被中断: " + taskName, e);
        }
        return result.get();
    }

    private synchronized void enqueue(Task task) {
        queue.add(task);
        if (!started) {
            started = true;
            Thread.ofVirtual().name("mapshim-" + name).start(this::drain);
        }
    }

    /** 单 worker：严格按入队序逐个执行，常驻直至 JVM 退出 */
    private void drain() {
        while (true) {
            try {
                Task task = queue.take();
                execute(task);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void execute(Task task) {
        CURRENT.set(this);
        long start = System.nanoTime();
        PipelineContext ctx = task.ctx();
        if (ctx != null) {
            // domain 盖章（同 Strand.executeTask）：owner 换为执行域
            PipelineContext.establish(domain == null ? ctx
                    : new PipelineContext(domain.type(), domain.id(), ctx.kinds, ctx.mode, ctx.recv));
        }
        try {
            task.body().run();
        } catch (Throwable t) {
            log.error("shim [{}] 任务失败: {}", name, task.name(), t);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            if (costMs >= SLOW_TASK_WARN_MS) {
                log.warn("shim [{}] 慢任务: {} 耗时 {}ms", name, task.name(), costMs);
            }
            CURRENT.remove();
            if (ctx != null) {
                PipelineContext.clear();
            }
        }
    }

    /** 当前线程是否在本 shim 的任务体内执行 */
    public boolean onShim() {
        return CURRENT.get() == this;
    }

    /** 离散断言：当前线程不在本 shim 上时告警（迁移期诊断，对齐 Strand.checkOnStrand 的 warn 模式） */
    public void checkOnShim(String what) {
        if (!onShim()) {
            log.warn("off-shim 访问 [{}]，期望 shim [{}]，当前线程 {}", what, name, Thread.currentThread());
        }
    }

    @Override
    public String toString() {
        return "shim[" + name + "]";
    }
}
