package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务完成（SHOW_STATUS_INFO quest 体完成分支）：completionTime 为 UTC ms 原值，
 * wire 文件时间换算归版本实现。
 */
public record QuestCompletedEvent(int questId, long completionTime) implements ServerEvent {
}
