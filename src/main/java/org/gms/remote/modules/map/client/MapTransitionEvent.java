package org.gms.remote.modules.map.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * PLAYER_MAP_TRANSFER 语义事件：玩家切图完成确认（零载荷——包体服务端不消费）。
 * 语义后果（切图标志复位 / homing beacon 重挂 / mob 视图重建）全部归 gameplay 地图域。
 */
public record MapTransitionEvent() implements ClientEvent {

    @Override
    public Module module() {
        return Module.MAP;
    }
}
