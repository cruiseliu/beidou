package org.gms.remote.modules.map.client;

import org.gms.remote.AbstractModule;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.remote.modules.map.server.AckMoveMonsterEvent;
import org.gms.remote.modules.map.server.CharacterMoveEvent;
import org.gms.remote.modules.map.server.MonsterMoveEvent;

import java.util.List;

/**
 * 语义模块：地图域（移动中继）。
 *
 * <p>S→C 视图：移动中继语义事件——元素序列由版本 router 对称重放为 wire 包；本模块不决定
 * 接收方，收播过滤归地图侧（地图决定发谁，包的构建归 remote，§3）。C→S Handler：玩家/mob
 * 控制移动语义入口。
 */
public abstract class MapModule extends AbstractModule {

    /**
     * 某角色移动了（他人流中继，接收方连接视角的语义投递）。
     * 由地图域在广播时点对每个接收方调用。
     */
    public final void characterMove(int charId, List<MoveElement> movements) {
        post(new CharacterMoveEvent(charId, movements));
    }

    /**
     * mob 移动 ack（controller 连接直发；载荷依赖 mob 状态，由地图域在 map actor
     * 任务体内调用）。与历史 PacketCreator.moveMonsterResponse 逐字节一致。
     */
    public final void ackMoveMonster(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel) {
        post(new AckMoveMonsterEvent(oid, moveid, currentMp, useSkills, skillId, skillLevel));
    }

    /**
     * 某怪物移动了（他人流中继，接收方连接视角的语义投递）。
     * 由地图域在广播时点对每个受众调用。
     */
    public final void monsterMove(MonsterMove move) {
        post(new MonsterMoveEvent(move));
    }

    /** 移动语义入口（player actor strand 上执行；元素由 gms083 纯解码产出）。 */
    public interface Handler {
        void movePlayer(List<MoveElement> elements);

        /** mob 控制移动语义入口（MOVE_LIFE；player strand 快照过界，map 域应用）。 */
        void moveLife(MoveLife life);
    }
}
