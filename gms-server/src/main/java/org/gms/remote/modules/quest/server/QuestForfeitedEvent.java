package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务放弃（SHOW_STATUS_INFO quest 体放弃分支：状态位恒 0，无进度段）。
 */
public record QuestForfeitedEvent(int questId) implements ServerEvent {
}
