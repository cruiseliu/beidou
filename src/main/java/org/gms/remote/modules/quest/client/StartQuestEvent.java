package org.gms.remote.modules.quest.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 接取任务意图（QUEST_ACTION action=1）：scriptRequirement 判定（直连 vs 脚本会话）、
 * 接取条件校验与执行归 gameplay 任务域。
 *
 * @param questId 任务 id
 * @param npc     发起 NPC
 */
public record StartQuestEvent(int questId, int npc) implements ClientEvent {

    @Override
    public Module module() {
        return Module.QUEST;
    }
}
