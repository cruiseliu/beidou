package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务系列终结标记（任务链无下一环时 complete 收尾）：客户端任务引导——该任务已可在
 * 指定 NPC 处交付（wire = UPDATE_QUEST_INFO type 8，交付分支形态归版本实现）。
 */
public record QuestSeriesCompleteEvent(int questId, int npc) implements ServerEvent {
}
