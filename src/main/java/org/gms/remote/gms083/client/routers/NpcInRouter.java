package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.NPCActionPacket;
import org.gms.remote.gms083.client.packets.NPCTalkMorePacket;
import org.gms.remote.gms083.client.translate.NpcEchoTranslator;
import org.gms.remote.gms083.client.translate.NpcTalkMoreTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * NPC 域 in-route：NPC_ACTION（客户端动画状态机 loopback——echo 形态事件，零语义消费）、
 * NPC_TALK_MORE（对话续行）。暂不设 strict canary 窗口：续行 gameplay 走脚本管理器，
 * 脚本侧 ref/Client 导航属既定形态（转职广播 CharacterRef.unref 实证触发 ref 哨），
 * 窗口待脚本层迁移后纳入。
 */
public final class NpcInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case NPC_ACTION -> emit(opcode, in, NPCActionPacket::decode, NpcEchoTranslator::new, player);
            case NPC_TALK_MORE -> emit(opcode, in, NPCTalkMorePacket::decode, NpcTalkMoreTranslator::new, player);
            default -> {
                return false;
            }
        }
        return true;
    }
}
