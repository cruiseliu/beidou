package org.gms.remote;

/**
 * 合并域句柄：{@code RemoteClient.update()} 开启，close 时统一发送域内积攒的通知。
 * 本身不携带状态——队列在 RemoteClient 内（见其类注释）；嵌套 update() 返回空句柄，
 * 由最外层收口。方法与 RemoteClient 的语义调用一一对应（链式便捷）。
 */
public interface RemoteUpdate extends AutoCloseable {
    RemoteUpdate updateStats(StatsUpdate update);

    RemoteUpdate updateSp(SpUpdate update);

    RemoteUpdate updateBasic(BasicUpdate update);

    RemoteUpdate unlockActions();

    /** 立即发送域内积攒的通知（等价 close）。 */
    void commit();

    /** commit 的别名（try-with-resources 兜底路径）。 */
    @Override
    void close();

    /** 空实现（无连接 / 嵌套域）。 */
    RemoteUpdate NOOP = new RemoteUpdate() {
        @Override
        public RemoteUpdate updateStats(StatsUpdate update) {
            return this;
        }

        @Override
        public RemoteUpdate updateSp(SpUpdate update) {
            return this;
        }

        @Override
        public RemoteUpdate updateBasic(BasicUpdate update) {
            return this;
        }

        @Override
        public RemoteUpdate unlockActions() {
            return this;
        }

        @Override
        public void commit() {
        }

        @Override
        public void close() {
        }
    };
}
