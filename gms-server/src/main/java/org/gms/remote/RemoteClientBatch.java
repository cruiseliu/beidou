package org.gms.remote;

/**
 * 合并域句柄：{@code RemoteClient.update()} 开启，语义调用平铺书写（句柄不参与路由）。
 * 事务/回滚语义见 gms-server/doc/package-client.md §2。
 */
public class RemoteClientBatch implements AutoCloseable {
    private RemoteClient client;
    private EventLog log;

    public RemoteClientBatch(RemoteClient client, EventLog log) {
        this.client = client;
        this.log = log;
    }

    public void commit() {
        client.batchEnd(log);
        log = null;
    }

    public void drop() {
        client.batchCancel(log);
        log = null;
    }

    @Override
    public void close() {
        commit();
    }
}
