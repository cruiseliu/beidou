package org.gms.remote.modules.quest.server;

import org.gms.client.quest.Quest;
import org.gms.remote.ServerEvent;

/**
 * 任务放弃全量通知（一次语义调用 → 版本实现按序发多帧）：本任务状态帧（NOT_STARTED +
 * 空进度）+ infoNumber 关联任务状态同步（quest.getInfo() 可空——关联任务进度不清，
 * 部分任务不可回退）。实体档（InitializeEvent 同型）：版本 route 经统一冻结门在入域时点
 * 物化为成品帧（FrozenQuestForfeitEvent），wire 事实读于调用时点。
 */
public record QuestForfeitEvent(Quest quest) implements ServerEvent {
}
