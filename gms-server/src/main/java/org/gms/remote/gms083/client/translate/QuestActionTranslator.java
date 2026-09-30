package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.packets.QuestActionPacket;
import org.gms.remote.modules.quest.client.CompleteQuestEvent;
import org.gms.remote.modules.quest.client.ForfeitQuestEvent;
import org.gms.remote.modules.quest.client.RestoreLostItemEvent;
import org.gms.remote.modules.quest.client.ScriptedEndQuestEvent;
import org.gms.remote.modules.quest.client.ScriptedStartQuestEvent;
import org.gms.remote.modules.quest.client.StartQuestEvent;

/**
 * QUEST_ACTION 翻译：action 分支 1:1 映射到任务域语义事件（纯映射，NPC 临近/接取完成
 * 条件等校验全归 gameplay）。未知 action = 零语义事件（null，codec 已解出供日志观察）。
 * 无 before/afterEmit——legacy 各分支结尾均无 unlock 回包（对话解锁由 NPC_TALK 状态机
 * 负责），wire 后果零变化。
 */
public final class QuestActionTranslator implements InTranslator<QuestActionPacket.Action> {

    @Override
    public ClientEvent translate(QuestActionPacket.Action packet) {
        return switch (packet) {
            case QuestActionPacket.RestoreLostItem(var action, var questId, var unknownNpc, var itemId) ->
                    new RestoreLostItemEvent(questId, itemId);
            case QuestActionPacket.Start(var action, var questId, var npc, var claimedPos) ->
                    new StartQuestEvent(questId, npc);
            case QuestActionPacket.Complete(var action, var questId, var npc, var claimedPos, var selection) ->
                    new CompleteQuestEvent(questId, npc, selection);
            case QuestActionPacket.Forfeit(var action, var questId) -> new ForfeitQuestEvent(questId);
            case QuestActionPacket.ScriptedStart(var action, var questId, var npc, var claimedPos) ->
                    new ScriptedStartQuestEvent(questId, npc);
            case QuestActionPacket.ScriptedEnd(var action, var questId, var npc, var claimedPos) ->
                    new ScriptedEndQuestEvent(questId, npc);
            case QuestActionPacket.Unknown unknown -> null;
        };
    }
}
