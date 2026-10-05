package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务链续环引导（complete 后 WZ 声明下一环任务，NextQuestAction）：客户端任务引导——
 * 指定 NPC 处交付后接续下一环（wire = UPDATE_QUEST_INFO type 8，尾 short nextQuest，
 * 与 legacy updateQuestFinish 同宽同序；区别于 {@link QuestSeriesCompleteEvent} 的
 * 系列终结形态）。
 */
public record QuestSeriesContinueEvent(int questId, int npc, int nextQuest) implements ServerEvent {
}
