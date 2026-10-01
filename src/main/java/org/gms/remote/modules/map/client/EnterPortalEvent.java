package org.gms.remote.modules.map.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * CHANGE_MAP_SPECIAL 语义事件：玩家走脚本传送门的意图（portalName = 包内声明的传送门名；
 * 存在性/冷却/屏蔽校验与门脚本执行归 gameplay 地图域）。
 */
public record EnterPortalEvent(String portalName) implements ClientEvent {

    @Override
    public Module module() {
        return Module.MAP;
    }
}
