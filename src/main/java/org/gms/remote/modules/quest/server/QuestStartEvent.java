package org.gms.remote.modules.quest.server;

import org.gms.client.quest.Quest;
import org.gms.remote.ServerEvent;

/**
 * 任务接取全量通知（一次语义调用 → 版本实现按序发多帧）：主任务状态帧 +
 * infoNumber 关联任务状态同步（quest.getInfo() 可空）+ NPC 交付确认。
 * 实体档（InitializeEvent 同型）：版本 route 经统一冻结门在入域时点物化为成品帧
 * （FrozenQuestStartEvent），wire 事实读于调用时点。
 */
public record QuestStartEvent(Quest quest) implements ServerEvent {
}
