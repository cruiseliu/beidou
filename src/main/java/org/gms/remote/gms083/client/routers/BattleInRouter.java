package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.CloseRangeAttackPacket;
import org.gms.remote.gms083.client.translate.CloseRangeAttackTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * 战斗域 in-route：CLOSE_RANGE_ATTACK（近战攻击申报；伤害改写与广播编排在
 * gameplay Handler）。其余 damage 系 opcode（MAGIC/RANGED/SUMMON/TOUCH_MONSTER）迁移后
 * 归本 route。strict 窗口已开（观察轮）：Handler 仍是机械复制体、S→C 未语义化——
 * 窗口内命中点即后续迁移清单（先记录不修）。
 */
public final class BattleInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case CLOSE_RANGE_ATTACK -> strictWindow(player, opcode, () ->
                    emit(opcode, in, CloseRangeAttackPacket::decode, CloseRangeAttackTranslator::new, player));
            default -> {
                return false;
            }
        }
        return true;
    }
}
