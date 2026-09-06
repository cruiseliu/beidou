package org.gms.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 角色级串行执行队列（actor mailbox）：归属同一 client 的全部操作（包处理、定时器回调、
 * 保存、脚本）顺序执行，是角色逻辑无锁化的基础设施。迁移路线见 doc 内设计讨论，
 * 语义契约如下。
 *
 * <ul>
 *   <li><b>串行</b>：单 worker 虚拟线程 + FIFO 队列，任务逐个执行；任务内阻塞（DB 等）
 *       卸载载体线程，不占 OS 线程，但会阻塞本 strand 后续任务（这正是串行语义）。</li>
 *   <li><b>重入</b>：worker 执行任务期间 {@code CURRENT} 指向本 strand；
 *       {@link #run} 在 strand 上时 inline 执行（等价同步调用），否则入队等待。
 *       <b>禁止在 strand 上等待另一个 strand</b>（跨 client 交互未来走 host 消息，不走互等）。</li>
 *   <li><b>排空终止</b>：{@link #close(Runnable)} 停止接收 → 排空剩余任务 → 执行 finalizer
 *       （保证最后一个），finalizer 之后不可能再有任务。</li>
 *   <li><b>异常隔离</b>：单任务异常记日志吞掉，worker 与会话存活（对齐 ScriptTimers 契约）。</li>
 *   <li><b>诊断</b>：慢任务（&gt;5s）完成时告警；卡死任务（&gt;30s）由共享看门狗周期告警并 dump 栈。</li>
 * </ul>
 */
public final class Strand implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Strand.class);

    private static final long SLOW_TASK_WARN_MS = 5_000;
    private static final long STUCK_TASK_WARN_MS = 30_000;
    private static final Task END = new Task("<close>", null, null);

    /** 当前线程正在执行的 strand（重入判定）；worker 任务执行期间置位 */
    private static final ThreadLocal<Strand> CURRENT = new ThreadLocal<>();

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Set<Strand> LIVE = ConcurrentHashMap.newKeySet();
    /** 共享看门狗：周期检查所有存活 strand 的卡死任务 */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "strand-watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        WATCHDOG.scheduleAtFixedRate(Strand::checkStuckTasks, 15, 15, TimeUnit.SECONDS);
    }

    private final String name;
    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    /** post/run 与 close 的接收状态机；队列本身线程安全，状态翻转需要原子 */
    private final Object stateLock = new Object();

    private boolean accepting = true;
    private boolean closed = false;
    private volatile Task currentTask;
    private volatile long currentTaskStartNanos;

    private Strand(String name) {
        this.name = name;
        this.worker = Thread.ofVirtual().name("strand-" + name).unstarted(this::workLoop);
    }

    /** 创建并启动 strand；name 用于线程命名与日志关联（建议 clientSessionId） */
    public static Strand create(String name) {
        Strand strand = new Strand(name + "-" + SEQ.incrementAndGet());
        LIVE.add(strand);
        strand.worker.start();
        return strand;
    }

    private record Task(String name, Runnable body, CountDownLatch done) {
    }

    /** 入队不等待；close 后调用抛 IllegalStateException（响亮失败，迟到任务由调用侧静默） */
    public void post(String taskName, Runnable body) {
        enqueue(new Task(taskName, body, null));
    }

    /**
     * 在本 strand 上执行：已在 strand 上则 inline（等价同步调用）；否则入队并阻塞等待完成。
     * 等待方不得是其它 strand 的任务（禁止跨 strand 互等）。
     */
    public void run(String taskName, Runnable body) {
        if (onStrand()) {
            body.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        enqueue(new Task(taskName, () -> {
            try {
                body.run();
            } finally {
                done.countDown();
            }
        }, done));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 strand 任务被中断: " + taskName, e);
        }
    }

    /**
     * 在本 strand 上执行并取回结果：已在本 strand 上则 inline；否则入队并阻塞等待。
     * 等待方不得持有 strand 任务可能需要的锁/事务（如持有 DB 写事务等 strand 结果 = 死锁配方）。
     */
    public <T> T supply(String taskName, java.util.function.Supplier<T> task) {
        if (onStrand()) {
            return task.get();
        }
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        enqueue(new Task(taskName, () -> {
            try {
                result.set(task.get());
            } finally {
                done.countDown();
            }
        }, done));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 strand 任务被中断: " + taskName, e);
        }
        return result.get();
    }

    private void enqueue(Task task) {
        synchronized (stateLock) {
            if (!accepting) {
                throw new IllegalStateException("strand [" + name + "] 已关闭，拒绝任务: " + task.name());
            }
            queue.add(task);
        }
    }

    private void workLoop() {
        try {
            while (true) {
                Task task = queue.take();
                if (task == END) {
                    Runnable fin = finalizerOnClose;
                    if (fin != null) {
                        executeTask(new Task("<finalizer>", fin, null));
                    }
                    return;
                }
                executeTask(task);
            }
        } catch (InterruptedException e) {
            log.error("strand [{}] worker 异常退出（close 必须经 END 哨兵，不应到达此处）", name, e);
        }
    }

    private void executeTask(Task task) {
        CURRENT.set(this);
        currentTask = task;
        currentTaskStartNanos = System.nanoTime();
        try {
            task.body().run();
        } catch (Throwable t) {
            log.error("strand [{}] 任务失败: {}", name, task.name(), t);
        } finally {
            long costMs = (System.nanoTime() - currentTaskStartNanos) / 1_000_000;
            if (costMs >= SLOW_TASK_WARN_MS) {
                log.warn("strand [{}] 慢任务: {} 耗时 {}ms", name, task.name(), costMs);
            }
            currentTask = null;
            CURRENT.remove();
        }
    }

    private Runnable finalizerOnClose;

    /**
     * 注册 close 排空后的收尾任务（重复设置覆盖，最后注册者生效；须在 close 前设置）。
     * finalizer 在 strand 上执行，做角色收尾（定时器关闭/资源释放），其后不可能再有本 strand 任务。
     */
    public synchronized void finalizeWith(Runnable finalizer) {
        if (!closed) {
            this.finalizerOnClose = finalizer;
        }
    }

    /**
     * 停止接收 → 排空剩余任务 → 执行 {@link #finalizeWith} 注册的收尾任务 → 结束。幂等。
     */
    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            accepting = false;
            queue.add(END);
        }
        LIVE.remove(this);
        // 不 join worker：finalizer 可能长时间运行，且 close 可能就在本 strand 任务内被调用
    }

    /** 是否已 close（停止接收并进入排空/终止流程）；关闭后无并发写者，外部可直接读状态 */
    public synchronized boolean isClosed() {
        return closed;
    }

    /** 当前线程是否在本 strand 上执行 */
    public boolean onStrand() {
        return CURRENT.get() == this;
    }

    /**
     * 离散断言：当前线程不在本 strand 上时告警。迁移期（M0~M3）固定 warn 模式不抛异常，
     * 由调用方撒在已迁移组件入口，暴露漏改道的调用路径；全量迁移后翻转为抛出。
     */
    public void checkOnStrand(String what) {
        if (!onStrand()) {
            log.warn("off-strand 访问 [{}]，期望 strand [{}]，当前线程 {}",
                    what, name, Thread.currentThread(), new IllegalStateException("off-strand"));
        }
    }

    private static void checkStuckTasks() {
        long nowNanos = System.nanoTime();
        for (Strand strand : LIVE) {
            Task task = strand.currentTask;
            if (task == null || task.body() == null) {
                continue;
            }
            long stuckMs = (nowNanos - strand.currentTaskStartNanos) / 1_000_000;
            if (stuckMs >= STUCK_TASK_WARN_MS) {
                log.warn("strand [{}] 任务疑似卡死: {} 已运行 {}ms，worker 栈: {}",
                        strand.name, task.name(), stuckMs, strand.worker.getStackTrace());
            }
        }
    }

    @Override
    public String toString() {
        return "strand[" + name + "]";
    }
}
