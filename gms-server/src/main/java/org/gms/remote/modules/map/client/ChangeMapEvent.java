package org.gms.remote.modules.map.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * CHANGE_MAP mode=0 语义事件：玩家经传送口移动 / 白名单 warp 的意图。
 * targetMapId = -1 = 按当前图传送门定义走门（portalName 主语义）；非 -1 = 客户端声明的
 * 目标图（GM warp / 新手剧情白名单校验）。门存在性/开闭状态/距离校验与实际走门归
 * gameplay 地图域。
 */
public record ChangeMapEvent(int targetMapId, String portalName) implements ClientEvent {

    @Override
    public Module module() {
        return Module.MAP;
    }
}
