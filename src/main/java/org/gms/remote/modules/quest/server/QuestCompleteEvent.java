package org.gms.remote.modules.quest.server;

import org.gms.client.quest.Quest;
import org.gms.remote.ServerEvent;

/**
 * 任务完成全量通知（一次语义调用 → 版本实现按序发多帧，仅本人帧；全图演出归地图广播，
 * 不在本事件）：完成状态帧 + 完成演出帧。
 * 实体档（InitializeEvent 同型）：版本 route 经统一冻结门在入域时点物化为成品帧
 * （FrozenQuestCompleteEvent），wire 事实读于调用时点。
 */
public record QuestCompleteEvent(Quest quest) implements ServerEvent {
}
