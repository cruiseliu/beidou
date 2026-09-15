package org.gms.remote.modules.map.client;

import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/**
 * 语义模块：地图域（移动中继）。
 *
 * <p>S→C 视图：移动中继编码——元素序列对称重放为 wire 包（成品，供调用方投递；
 * 本模块不决定接收方，收播过滤归地图侧）。C→S Handler：玩家/mob 控制移动语义入口。
 */
public interface MapModule {

    /**
     * 某角色移动了（他人流中继，接收方连接视角的语义投递）：编码并投递到本端连接。
     * 由地图域在广播时点对每个接收方调用（地图决定发谁，包的构建归 remote，§3）。
     */
    void characterMove(int charId, List<MoveElement> movements);

    /**
     * mob 移动 ack（controller 连接直发；载荷依赖 mob 状态，由地图域在 map actor
     * 任务体内调用）。与历史 PacketCreator.moveMonsterResponse 逐字节一致。
     */
    void ackMoveMonster(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel);

    /**
     * 某怪物移动了（他人流中继，接收方连接视角的语义投递）：编码并投递到本端连接。
     * 由地图域在广播时点对每个受众调用（地图决定发谁，包的构建归 remote，§3）。
     */
    void monsterMove(MonsterMove move);

    /** 移动语义入口（player actor strand 上执行；元素由 gms083 纯解码产出）。 */
    interface Handler {
        void movePlayer(List<MoveElement> elements);

        /** mob 控制移动语义入口（MOVE_LIFE；player strand 快照过界，map 域应用）。 */
        void moveLife(MoveLife life);
    }
}
