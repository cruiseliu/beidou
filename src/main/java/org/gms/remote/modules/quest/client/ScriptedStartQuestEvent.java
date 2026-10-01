package org.gms.remote.modules.quest.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 脚本化接取任务意图（QUEST_ACTION action=4）：入口名解析（WZ startscript / ESM）、
 * 脚本会话建立归 gameplay 任务域。与 {@link StartQuestEvent} 的分工是客户端 UI 状态——
 * action=4 由脚本任务面板发起，服务端不再回退直连接取。
 *
 * @param questId 任务 id
 * @param npc     发起 NPC
 */
public record ScriptedStartQuestEvent(int questId, int npc) implements ClientEvent {

    @Override
    public Module module() {
        return Module.QUEST;
    }
}
