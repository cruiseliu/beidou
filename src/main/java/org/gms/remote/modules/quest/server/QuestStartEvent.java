package org.gms.remote.modules.quest.server;

import org.gms.remote.ServerEvent;

/**
 * 任务接取全量通知（一次语义调用 → 版本实现按序发多帧）：主任务状态帧 +
 * infoNumber 关联任务状态同步（infoSync 可空）+ NPC 交付确认。
 * status/嵌套字段均 = QuestStatus 枚举值。
 */
public record QuestStartEvent(int questId, int status, int npc, String progressData,
                              QuestStateEvent infoSync) implements ServerEvent {
}
