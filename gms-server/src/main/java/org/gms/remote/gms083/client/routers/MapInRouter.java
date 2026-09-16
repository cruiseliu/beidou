package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.MoveLifePacket;
import org.gms.remote.gms083.client.packets.MovePlayerPacket;
import org.gms.remote.gms083.client.translate.MoveLifeTranslator;
import org.gms.remote.gms083.client.translate.MovePlayerTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * 地图域 in-route：MOVE_PLAYER（全服最高频包，strict canary 窗口）+ MOVE_LIFE
 * （mob 控制移动汇报）。应用与广播编排全部在 gameplay/地图侧；本类只编排解码与翻译。
 */
public final class MapInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case MOVE_PLAYER -> strictWindow(player, () ->
                    emit(opcode, in, MovePlayerPacket::decode, MovePlayerTranslator::new, player));
            case MOVE_LIFE -> emit(opcode, in, MoveLifePacket::decode, MoveLifeTranslator::new, player);
            default -> {
                return false;
            }
        }
        return true;
    }
}
