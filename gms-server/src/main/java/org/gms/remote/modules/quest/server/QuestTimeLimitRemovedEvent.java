package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务限时移除（UPDATE_QUEST_INFO 限时移除分支，放弃/完成时撤表）。
 */
public record QuestTimeLimitRemovedEvent(int questId) implements ServerEvent {
}
