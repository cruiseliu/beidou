package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.packets.NPCTalkMorePacket;
import org.gms.remote.modules.npc.client.NpcTalkMoreEvent;

/**
 * NPC_TALK_MORE 翻译：回显事实 1:1 映射到对话续行事件（纯映射）。
 * 脚本重入分流（ESM/任务/NPC 脚本）与 unlock 回包归 gameplay——本 op 无 wire 副作用。
 */
public final class NpcTalkMoreTranslator implements InTranslator<NPCTalkMorePacket.TalkMore> {

    @Override
    public ClientEvent translate(NPCTalkMorePacket.TalkMore packet) {
        return new NpcTalkMoreEvent(packet.lastMsg(), packet.action(), packet.text(), packet.selection());
    }
}
