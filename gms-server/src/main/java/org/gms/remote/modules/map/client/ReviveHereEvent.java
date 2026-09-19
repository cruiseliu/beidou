package org.gms.remote.modules.map.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * CHANGE_MAP mode=1 语义事件：死亡弹窗的复活意图（wheel = 客户端声明使用命运转盘原地
 * 复活）。存活判定以服务端事实为准（事件只是意图声明）；转盘持有校验、事件脚本复活、
 * 回程图 respawn 归 gameplay。targetMapId 承接 wire 同名字段——legacy 分支门
 * （-1 = 不处理）随其保留。
 */
public record ReviveHereEvent(boolean wheel, int targetMapId) implements ClientEvent {

    @Override
    public Module module() {
        return Module.MAP;
    }
}
