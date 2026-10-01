package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.NPCActionPacket;
import org.gms.remote.gms083.client.translate.NpcEchoTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * NPC 域 in-route：NPC_ACTION（客户端动画状态机 loopback——echo 形态事件，零语义消费）。
 * 语义对话类 opcode（NPC_TALK 等）迁移后归本 route。
 */
public final class NpcInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case NPC_ACTION -> emit(opcode, in, NPCActionPacket::decode, NpcEchoTranslator::new, player);
            default -> {
                return false;
            }
        }
        return true;
    }
}
