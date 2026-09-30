package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务到期作废（UPDATE_QUEST_INFO 到期分支）。
 */
public record QuestExpiredEvent(int questId) implements ServerEvent {
}
