package org.gms.remote.modules.map.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.awt.*;
import java.util.List;

/**
 * MOVE_LIFE 语义事件（gms083 codec 解码产物即事件本体，无再翻译）。rawActivity 保留
 * 原样字节——活动/技能判定与攻击门控会改写它，属 gameplay 语义，归地图域 verbatim 进行
 * （updatePosition monster 分支的位置应用语义同样在地图域）。
 */
public record MoveLife(int oid, short moveid, byte pNibbles, byte rawActivity, int skillId, int skillLv,
                       short pOption, Point startPos, List<MoveElement> elements) implements ClientEvent {

    @Override
    public Module module() {
        return Module.MAP;
    }
}
