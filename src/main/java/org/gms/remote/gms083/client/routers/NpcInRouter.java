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
 * NPC_TALK_MORE（对话续行，ref-only canary 窗口）。packet 哨暂缓：续行 gameplay 的脚本层
 * 存量 legacy-Client 导航（ESM gainItem → CharacterInventory.gainItem getClient，实证 trip；
 * InventoryManipulator 全链吃 Client，消除 = 背包公告路径迁移）。ref 哨已覆盖本 op 可纳入面
 * （tutorial/combat/quest 通过；flow 的转职广播 CharacterRef.unref 为已知残留，随脚本层迁移收敛）。
 */
public final class NpcInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case NPC_ACTION -> emit(opcode, in, NPCActionPacket::decode, NpcEchoTranslator::new, player);
            case NPC_TALK_MORE -> strictWindow(player, () ->
                    emit(opcode, in, NPCTalkMorePacket::decode, NpcTalkMoreTranslator::new, player), false);
            default -> {
                return false;
            }
        }
        return true;
    }
}
