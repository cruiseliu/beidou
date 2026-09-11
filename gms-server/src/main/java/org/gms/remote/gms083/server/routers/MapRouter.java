package org.gms.remote.gms083.server.routers;

import org.gms.net.packet.Packet;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.client.packets.MovePacket;
import org.gms.remote.modules.map.client.MapModule;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/** 地图域视图：移动中继编码（成品包，收播过滤归地图侧）。 */
public final class MapRouter implements MapModule {

    private final Gms083 client;

    public MapRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    public Packet movePlayer(int charId, List<MoveElement> elements) {
        return client.toLegacyPacket(MovePacket.relay(charId, elements));
    }
}
