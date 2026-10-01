package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.server.packets.V83Packet;

import java.util.List;

/**
 * freeze 的产物：任务完成（完成状态帧 + 完成演出帧，仅本人）在入域时点物化的成品帧。
 * wire 事实（questId/完成时间/效果码）在事件构造时点烘死，deliver 退化为按序投递。
 */
public record FrozenQuestCompleteEvent(List<V83Packet> frames) implements ServerEventBase {
}
