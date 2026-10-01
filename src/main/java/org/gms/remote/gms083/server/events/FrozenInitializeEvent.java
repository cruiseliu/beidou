package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.server.packets.V83Packet;

import java.util.List;

/**
 * freeze 的产物：入场初始化（SET_FIELD + 键位/快捷键/宏/自动用药）在入域时点物化的成品帧。
 * wire 事实（channel/buddyCapacity/linkedName/meso/time 与全部键位表）在事件构造时点烘死，
 * deliver 退化为按序投递。原 InitializeEvent 活引用（不完整冻结 FIXME）由此收口。
 */
public record FrozenInitializeEvent(List<V83Packet> frames) implements ServerEventBase {
}
