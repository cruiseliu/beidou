package org.gms.remote.modules.map.client;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventReceiver;
import org.gms.remote.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 地图域入站接收：移动语义事件 → 地图 Handler 裸参数直调。应用与广播编排在 gameplay/
 * 地图侧，本类只解包。
 */
public final class MapInbound implements ClientEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(MapInbound.class);

    @Override
    public Module module() {
        return Module.MAP;
    }

    @Override
    public void receive(Player player, ClientEvent event) {
        switch (event) {
            case MovePlayerEvent(var elements) -> player.clientEventHandlers().map().movePlayer(elements);
            case MoveLife life -> player.clientEventHandlers().map().moveLife(life);
            case MapTransitionEvent e -> player.clientEventHandlers().map().mapTransition();
            case EnterPortalEvent(var portalName) -> player.clientEventHandlers().map().enterPortal(portalName);
            default -> log.error("MapInbound 收到非本模块事件 {}", event.getClass().getName());
        }
    }
}
