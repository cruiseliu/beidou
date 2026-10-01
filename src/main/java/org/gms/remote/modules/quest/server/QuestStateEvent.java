package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务状态/进度更新（SHOW_STATUS_INFO quest 体，状态位分支）：进行中进度写入与
 * infoNumber 关联任务的状态同步共用本事件（status = QuestStatus 枚举值 0/1/2）。
 */
public record QuestStateEvent(int questId, int status, String progressData) implements ServerEvent {
}
