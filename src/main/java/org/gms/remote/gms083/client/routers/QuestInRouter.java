package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.QuestActionPacket;
import org.gms.remote.gms083.client.translate.QuestActionTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * 任务域 in-route：QUEST_ACTION（接取/完成/放弃/找回/脚本化接取完成，strict canary 窗口）。
 * 语义事件归 QUEST 域。
 */
public final class QuestInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case QUEST_ACTION -> strictWindow(player, () ->
                    emit(opcode, in, QuestActionPacket::decode, QuestActionTranslator::new, player));
            default -> {
                return false;
            }
        }
        return true;
    }
}
