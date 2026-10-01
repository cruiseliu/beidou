package org.gms.infra;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 按 id 定时层（两层结构的上层，TimeoutHelper 风格）：构造时绑定 listener 与 strand 供给，
 * 之后 schedule(id, deadline) / cancel(id)。冷却、buff、宠物饥饿/过期等
 * "按 id 定时"的场景共用此形态；底层能力来自 {@link DeadlineTimer}。
 *
 * <p>strand 经 {@link Supplier} 惰性解析（每次 schedule 时取当前值）：跨连接复用的宿主
 * （如换频道重绑 client 的角色组件）自动跟随最新 strand，无需重建容器。
 *
 * <ul>
 *   <li><b>replace 语义</b>：同 id 重复 schedule = 取消旧任务再登记（re-arm 直接覆盖；
 *       旧 TimeoutHelper 覆盖 map 条目却不取消旧 future 的隐患在此消除）。</li>
 *   <li><b>scheduleOrRun</b>：deadline 已过期时在调用线程 inline 执行 listener 并返回 true
 *       （调用方应在本 strand 上，否则告警），未过期登记并返回 false——
 *       "已过期立刻执行且马上知道结果"的游戏逻辑用这一个调用表达。</li>
 *   <li><b>listener 签名</b>：{@code fire(id, scheduledMs)}，scheduledMs 为登记的 deadline
 *       原值，re-arm 从它推算（饥饿的 {@code schedule(id, scheduledMs + interval)} 惯例）。</li>
 *   <li><b>late-cancel 尽力而为</b>：cancel 对"引擎已投递、尚未在 strand 上执行"的任务无效，
 *       listener 仍会跑一次（与旧 TimeoutHelper 一致；需要精确抢占的语义由 listener 内
 *       状态检查兜底）。</li>
 *   <li><b>close</b>：取消全部待触发任务（登出收尾）；之后 schedule 静默丢弃。</li>
 * </ul>
 */
public final class KeyedTimers implements AutoCloseable {
    /** id → 待触发句柄；fire 时经双参 remove 确认"确系本人"才摘除 */
    private final Map<Integer, DeadlineTimer.TimerHandle> pending = new ConcurrentHashMap<>();
    private final Supplier<Strand> strandSupplier;
    private final TimerListener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @FunctionalInterface
    public interface TimerListener {
        void fire(int id, long scheduledMs);
    }

    public KeyedTimers(Supplier<Strand> strandSupplier, TimerListener listener) {
        this.strandSupplier = strandSupplier;
        this.listener = listener;
    }

    /** 登记定时（同 id 取旧排新）；已过期不 inline，尽快触发 */
    public void schedule(int id, long deadlineMs) {
        replace(id, deadlineMs, false);
    }

    /**
     * 登记定时，deadline 已过期（{@code deadlineMs <= now}）则立即 inline 执行并返回 true，
     * 否则登记并返回 false。同 id 取旧排新；close 后仅未过期分支静默丢弃（返回 false）。
     */
    public boolean scheduleOrRun(int id, long deadlineMs) {
        return replace(id, deadlineMs, true);
    }

    private boolean replace(int id, long deadlineMs, boolean runIfExpired) {
        cancel(id);
        if (runIfExpired && deadlineMs <= DeadlineTimer.now()) {
            Strand strand = strandSupplier.get();
            if (strand != null) {
                strand.checkOnStrand("KeyedTimers.scheduleOrRun(" + id + ")");
            }
            listener.fire(id, deadlineMs);
            return true;
        }
        if (closed.get()) {
            return false;
        }
        // self 自引用：fire 时先双参 remove 确认登记项仍是本人（replace 竞态下不误删新任务）
        DeadlineTimer.TimerHandle[] self = new DeadlineTimer.TimerHandle[1];
        self[0] = DeadlineTimer.schedule(strandSupplier.get(), deadlineMs, "KeyedTimers#" + id, scheduledMs -> {
            if (pending.remove(id, self[0])) {
                listener.fire(id, scheduledMs);
            }
        });
        pending.put(id, self[0]);
        return false;
    }

    /** 取消待触发任务；未知/已触发 id 静默，返回是否确实取消了一次 */
    public boolean cancel(int id) {
        DeadlineTimer.TimerHandle handle = pending.remove(id);
        return handle != null && handle.cancel();
    }

    /** 取消全部待触发任务（登出收尾）；此后 schedule 静默丢弃 */
    @Override
    public void close() {
        closed.set(true);
        pending.forEach((id, handle) -> handle.cancel());
        pending.clear();
    }
}
