package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务交付确认（UPDATE_QUEST_INFO 交付分支）：告知客户端任务在指定 NPC 处可交付。
 */
public record QuestNpcDeliveryEvent(int questId, int npc) implements ServerEvent {
}
