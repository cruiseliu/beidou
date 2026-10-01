package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.remote.modules.map.client.MapTransitionEvent;

/**
 * PLAYER_MAP_TRANSFER codec（v83）：零载荷包——历史 handler 不读包体，decode 产物即
 * 语义事件本体（MoveLife 先例：无再翻译语义）。
 */
public final class MapTransferPacket {

    private MapTransferPacket() {
    }

    public static MapTransitionEvent decode(ByteBufReader p) {
        return new MapTransitionEvent();
    }
}
