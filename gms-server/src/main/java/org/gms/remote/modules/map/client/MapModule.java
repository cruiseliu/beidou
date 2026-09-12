package org.gms.remote.modules.map.client;

import org.gms.net.packet.Packet;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.awt.*;
import java.util.List;

/**
 * 语义模块：地图域（移动中继）。
 *
 * <p>S→C 视图：移动中继编码——元素序列对称重放为 wire 包（成品，供调用方投递；
 * 本模块不决定接收方，收播过滤归地图侧）。C→S Handler：玩家/mob 控制移动语义入口。
 */
public interface MapModule {

    /**
     * 移动中继编码（他人流广播的成品包）：int charId + 元素序列对称重放。
     * 与历史 PacketCreator.movePlayer 逐字节一致（对称 codec，round-trip 验证）。
     */
    Packet movePlayer(int charId, List<MoveElement> elements);

    /**
     * mob 移动 ack（controller 连接直发；载荷依赖 mob 状态，由地图域在 map actor
     * 任务体内调用）。与历史 PacketCreator.moveMonsterResponse 逐字节一致。
     */
    void ackMoveMonster(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel);

    /**
     * mob 移动中继编码（他人流广播的成品包）。与历史 PacketCreator.moveMonster
     * 逐字节一致。
     */
    Packet relayMoveMonster(int oid, boolean skillPossible, int skill, int skillId, int skillLevel,
                            int pOption, Point startPos, List<MoveElement> elements);

    /** 移动语义入口（player actor strand 上执行；元素由 gms083 纯解码产出）。 */
    interface Handler {
        void movePlayer(List<MoveElement> elements);

        /** mob 控制移动语义入口（MOVE_LIFE；player strand 快照过界，map 域应用）。 */
        void moveLife(MoveLife life);
    }
}
