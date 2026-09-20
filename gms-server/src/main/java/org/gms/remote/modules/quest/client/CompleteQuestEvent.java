package org.gms.remote.modules.quest.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 完成任务意图（QUEST_ACTION action=2）：完成条件校验、选择型奖励与脚本会话分流归
 * gameplay 任务域。
 *
 * @param questId   任务 id
 * @param npc       交付 NPC
 * @param selection 客户端选择（未携带为 null；实测真客户端恒带，无奖励选择时为 -1）
 */
public record CompleteQuestEvent(int questId, int npc, Integer selection) implements ClientEvent {

    @Override
    public Module module() {
        return Module.QUEST;
    }
}
