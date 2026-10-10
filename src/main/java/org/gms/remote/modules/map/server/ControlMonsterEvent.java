package org.gms.remote.modules.map.server;

import org.gms.client.character.MapView;
import org.gms.remote.ServerEvent;

/**
 * 授控（S→C 语义事件）：接收方连接开始控制该怪。载荷 = {@link MapView.MonsterView}
 * 值快照（无活引用），版本 route 在 freeze 时点物化全身帧。
 */
public record ControlMonsterEvent(MapView.MonsterView view, boolean immediateAggro) implements ServerEvent {
}
