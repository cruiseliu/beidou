package org.gms.remote.modules.quest.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 脚本化完成任务意图（QUEST_ACTION action=5）：入口名解析（WZ endscript / ESM）、
 * 完成条件校验与脚本会话建立归 gameplay 任务域。
 *
 * @param questId 任务 id
 * @param npc     交付 NPC
 */
public record ScriptedEndQuestEvent(int questId, int npc) implements ClientEvent {

    @Override
    public Module module() {
        return Module.QUEST;
    }
}
