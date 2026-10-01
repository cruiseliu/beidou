package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务状态/进度更新（SHOW_STATUS_INFO quest 体，状态位分支）：进行中进度写入与
 * infoNumber 关联任务的状态同步共用本事件（status = QuestStatus 枚举值 0/1/2）。
 * progress = 语义进度表（mobId → 进度值，插入序），wire 串接归版本实现。
 */
public record QuestStateEvent(int questId, int status, Map<Integer, String> progress) implements ServerEvent {
    public QuestStateEvent {
        progress = new LinkedHashMap<>(progress);   // 入域拷贝，保插入序
    }
}
