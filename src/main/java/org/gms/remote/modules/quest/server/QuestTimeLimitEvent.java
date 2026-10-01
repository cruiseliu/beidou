package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务限时追加（UPDATE_QUEST_INFO 限时分支）：remainingMillis = 距到期剩余毫秒。
 */
public record QuestTimeLimitEvent(int questId, long remainingMillis) implements ServerEvent {
}
