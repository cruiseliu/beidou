package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

/**
 * 授控（S→C 语义事件）：接收方连接开始控制该怪。载荷 = map object id——
 * 版本 route 在 freeze 时点从接收方 map view 取 {@code MonsterView} 值快照物化全身帧。
 */
public record ControlMonsterEvent(int oid, boolean immediateAggro) implements ServerEvent {
}
