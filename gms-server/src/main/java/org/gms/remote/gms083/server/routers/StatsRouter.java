package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventDest;
import org.gms.remote.modules.stats.StatsModule;
import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.modules.stats.server.StatsEvent;
import org.gms.remote.modules.stats.server.StatsUpdate;

/**
 * stats 域 route：出脸（updateStats）+ deliver/flush 下沉。事件收窄仅限本域
 * （owner 标记保证只收到本模块事件）。
 */
public final class StatsRouter implements StatsModule, ServerEventDest {
    private final Gms083 client;

    public StatsRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    public void updateStats(StatsUpdate update) {
        client.schedule(this, new StatsEvent(update));
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
