package org.gms.remote;

/**
 * 合并域句柄：{@code RemoteClient.update()} 开启，close 时统一发送域内积攒的通知。
 * 与 RemoteClient 返回相同的模块单例——缓冲与冲刷由客户端自身的 depth 状态决定，
 * 句柄仅提供会话内书写的入口与生命周期。语义调用平铺书写，不再链式。
 */
public interface RemoteUpdate extends AutoCloseable {
    StatsModule stats();

    SkillsModule skills();

    BasicModule basic();

    CooldownModule cooldown();

    InventoryModule inventory();

    /**
     * 丢弃本作用域积累的全部语义事件并结束（P2：全域 drop transaction，O(1) 弃段）。
     * 所有模块一并生效——横切能力而非领域特例。
     */
    void drop();

    /** 立即发送域内积攒的通知（等价 close）。 */
    void commit();

    /** commit 的别名（try-with-resources 兜底路径）。 */
    @Override
    void close();

    /** 空实现（无连接）：模块访问器路由到静默空模块，语义调用安全无害。 */
    RemoteUpdate NOOP = new RemoteUpdate() {
        private final DisconnectedClient silent = new DisconnectedClient();

        @Override public StatsModule stats() { return silent; }
        @Override public SkillsModule skills() { return silent; }
        @Override public BasicModule basic() { return silent; }
        @Override public CooldownModule cooldown() { return silent; }
        @Override public InventoryModule inventory() { return silent; }
        @Override public void commit() {
        }

        @Override
        public void close() {
        }

        @Override
        public void drop() {
        }
    };
}
