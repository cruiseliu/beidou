package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.modules.stats.StatsModule;
import org.gms.remote.modules.stats.server.StatsEvent;

/**
 * stats 域 route：出脸继承自 {@link StatsModule}（API → 事件在基类），本类承载 emit/deliver/flush。
 * 事件收窄仅限本域（owner 标记保证只收到本模块事件）。
 */
public final class StatsRouter extends StatsModule implements ServerEventDest {
    private final Gms083 client;

    public StatsRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    @Override
    public void deliver(ServerEventBase r) {
        if (r instanceof StatsEvent(var u)) {
            client.translators().statsT.onStats(u);
        }
    }

    @Override
    public void flush() {
        client.translators().statsT.flush().forEach(client::send);
    }
}
