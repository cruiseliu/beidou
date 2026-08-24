package org.gms.remote;

/**
 * 语义事务：按游戏逻辑的语义边界（一次转职、一次升级、一次商店购买……）积攒通知，
 * commit 时由版本编码器决定 wire 上如何合并组包。
 *
 * <p>使用方式（try-with-resources，close 即 commit）：
 * <pre>{@code
 * try (RemoteUpdate u = chr.remote().update()) {
 *     u.updateStats(new StatsUpdate().set(Stat.STR, 5).ap(3));
 *     u.updateSp(new SpUpdate(jobId, spVal, spBuckets));
 *     u.unlockActions();
 * }
 * }</pre>
 *
 * <p>语义细则：
 * <ul>
 * <li>close 恒 flush——这是通知缓冲不是数据库事务，游戏状态在组件里已经改了，
 *     客户端必须看到，因此没有回滚/discard；</li>
 * <li>commit/close 幂等，第二次调用为 no-op；</li>
 * <li>同一 RemoteClient 同时只允许一个打开的事务，嵌套 update() 抛 IllegalStateException
 *     （一个 handler = 一个事务）；</li>
 * <li>事务不得跨线程使用；底层发送线程安全性与 Client.sendPacket 一致。</li>
 * </ul>
 */
public interface RemoteUpdate extends AutoCloseable {
    /** 面板属性 + hp/mp/ap 通知（stats 域，见 StatsUpdate）。可多次调用，同字段后写覆盖。 */
    RemoteUpdate updateStats(StatsUpdate update);

    /** SP 通知（技能域，见 SpUpdate）。可多次调用，后写覆盖。 */
    RemoteUpdate updateSp(SpUpdate update);

    /** 解除客户端动作锁（独立语义：v83 中并入 STAT_CHANGED 首字节；无其他内容时=空更新包）。 */
    RemoteUpdate unlockActions();

    /** 立即组包发送积攒的通知。 */
    void commit();

    /** commit 的别名（try-with-resources 兜底路径）。 */
    @Override
    void close();
}
