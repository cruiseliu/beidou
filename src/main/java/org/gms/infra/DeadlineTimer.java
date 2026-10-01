package org.gms.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 裸定时层（两层结构的底层）：到期时间驱动的一次性任务 + 可取消句柄。
 * 全局共享单线程触发器，只负责"到点投递"，不承载业务逻辑；
 * 按 id 定时、inline 触发等高层语义见 {@link KeyedTimers}。
 *
 * <ul>
 *   <li><b>到期时间而非延时</b>：API 接收绝对 deadline，触发任务时把登记的 deadline 原样
 *       交给任务（{@link DeadlineTask}），re-arm 从原 deadline 推算，无累积误差。</li>
 *   <li><b>时钟同源</b>：deadline 与服务端游戏时钟（{@code Server.getCurrentTime()}，粗粒度
 *       步进）同一基准——Server 启动时经 {@link #useClock} 注入；延迟按同一时钟换算，
 *       触发天然落在游戏时钟语义上（最多滞后一个 tick）。</li>
 *   <li><b>strand 投递</b>：到点把任务 post 进登记的 strand，与角色操作串行；
 *       strand 已关闭（登出竞态）则静默丢弃。</li>
 * </ul>
 */
public final class DeadlineTimer {
    private static final Logger log = LoggerFactory.getLogger(DeadlineTimer.class);

    /** 定时任务：scheduledMs = 登记时的 deadline 原值（非触发时刻） */
    @FunctionalInterface
    public interface DeadlineTask {
        void run(long scheduledMs);
    }

    /** 待触发定时器句柄；cancel 返回是否确实拦下了一次待触发任务 */
    public interface TimerHandle {
        boolean cancel();
    }

    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** 共享触发器：单线程足够（只做"睡眠到点 + 投递"） */
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "deadline-timer");
        t.setDaemon(true);
        return t;
    });

    private DeadlineTimer() {
    }

    /** 注入游戏时钟（Server 启动时调用一次）；未注入时退化为墙钟 */
    public static void useClock(LongSupplier supplier) {
        clock = supplier;
    }

    public static long now() {
        return clock.getAsLong();
    }

    /**
     * 登记一次性任务：deadline 到点把 {@code task.run(deadlineMs)} post 进 strand。
     * deadline 已过期 → 尽快触发（不 inline，inline 语义在 {@link KeyedTimers#scheduleOrRun}）。
     */
    public static TimerHandle schedule(Strand strand, long deadlineMs, String taskName, DeadlineTask task) {
        long delayMs = Math.max(0, deadlineMs - now());
        ScheduledFuture<?>[] self = new ScheduledFuture<?>[1];
        self[0] = SCHEDULER.schedule(() -> {
            try {
                strand.post(taskName, () -> task.run(deadlineMs));
            } catch (Throwable e) {
                // strand 已关闭 / 暂不可用：定时触发是尽力而为，共享触发器线程绝不因单个任务死亡
                log.debug("strand [{}] 丢弃到期任务 {} (deadline={})", strand, taskName, deadlineMs, e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        return new Handle(self[0]);
    }

    private record Handle(ScheduledFuture<?> future) implements TimerHandle {
        @Override
        public boolean cancel() {
            return future.cancel(false);
        }
    }
}
