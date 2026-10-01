package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.modules.map.client.MapTransitionEvent;

/**
 * PLAYER_MAP_TRANSFER 翻译：恒等——零载荷包，decode 产物即语义事件本体。
 */
public final class MapTransitionTranslator implements InTranslator<MapTransitionEvent> {

    @Override
    public ClientEvent translate(MapTransitionEvent event) {
        return event;
    }
}
