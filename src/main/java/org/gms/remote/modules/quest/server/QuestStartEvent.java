package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务接取全量通知（一次语义调用 → 版本实现按序发多帧）：主任务状态帧 +
 * infoNumber 关联任务状态同步（infoSync 可空）+ NPC 交付确认。
 * status = QuestStatus 枚举值；progress = 语义进度表（mobId → 进度值，插入序），
 * wire 串接归版本实现。
 */
public record QuestStartEvent(int questId, int status, int npc, Map<Integer, String> progress,
                              QuestStateEvent infoSync) implements ServerEvent {
    public QuestStartEvent {
        progress = new LinkedHashMap<>(progress);   // 入域拷贝，保插入序
    }
}
