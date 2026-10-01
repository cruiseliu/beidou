package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.server.packets.V83Packet;

import java.util.List;

/**
 * freeze 的产物：任务放弃（本任务状态帧 + infoNumber 关联任务状态同步）在入域时点
 * 物化的成品帧。wire 事实（双方 questId/status/进度串与关联任务存在性）在事件构造时点
 * 烘死，deliver 退化为按序投递。
 */
public record FrozenQuestForfeitEvent(List<V83Packet> frames) implements ServerEventBase {
}
