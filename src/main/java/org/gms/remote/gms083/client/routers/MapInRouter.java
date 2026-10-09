package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.ChangeMapPacket;
import org.gms.remote.gms083.client.packets.ChangeMapSpecialPacket;
import org.gms.remote.gms083.client.packets.MapTransferPacket;
import org.gms.remote.gms083.client.packets.MoveLifePacket;
import org.gms.remote.gms083.client.packets.MovePlayerPacket;
import org.gms.remote.gms083.client.translate.ChangeMapTranslator;
import org.gms.remote.gms083.client.translate.EnterPortalTranslator;
import org.gms.remote.gms083.client.translate.MapTransitionTranslator;
import org.gms.remote.gms083.client.translate.MoveLifeTranslator;
import org.gms.remote.gms083.client.translate.MovePlayerTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * 地图域 in-route：MOVE_PLAYER（全服最高频包，strict canary 窗口）+ MOVE_LIFE
 * （mob 控制移动汇报）+ PLAYER_MAP_TRANSFER（切图完成确认，strict canary 窗口）+
 * CHANGE_MAP_SPECIAL（脚本传送门入口）+ CHANGE_MAP（走门/复活/白名单 warp）——
 * 两个换图 op 只开 ref 哨：换图主干经 legacy Client 触达 MapFactory/PlayerStorage/
 * disconnect 等，涉及面太广，packet-strict 哨暂缓纳入（踩点清单见 tmp/portal-bug.log，
 * 待导航迁移后改回 strictWindow(player, body)）。
 * 应用与广播编排全部在 gameplay/地图侧；本类只编排解码与翻译。
 */
public final class MapInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case MOVE_PLAYER -> strictWindow(player, opcode, () ->
                    emit(opcode, in, MovePlayerPacket::decode, MovePlayerTranslator::new, player));
            case MOVE_LIFE -> emit(opcode, in, MoveLifePacket::decode, MoveLifeTranslator::new, player);
            case PLAYER_MAP_TRANSFER -> strictWindow(player, opcode, () ->
                    emit(opcode, in, MapTransferPacket::decode, MapTransitionTranslator::new, player), false);
            case CHANGE_MAP_SPECIAL -> strictWindow(player, opcode, () ->
                    emit(opcode, in, ChangeMapSpecialPacket::decode, EnterPortalTranslator::new, player), false);
            case CHANGE_MAP -> strictWindow(player, opcode, () ->
                    emit(opcode, in, ChangeMapPacket::decode, ChangeMapTranslator::new, player), false);
            default -> {
                return false;
            }
        }
        return true;
    }
}
