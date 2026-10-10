package org.gms.remote.modules.map.server;

import org.gms.client.character.MapView;
import org.gms.remote.ServerEvent;

/**
 * 怪物落地（S→C 语义事件）：帧形态（普通落地/淡入/特演/假怪）归版本实现。
 * 载荷 = {@link MapView.MonsterView} 值快照（无活引用），版本 route 在 freeze 时点物化帧。
 */
public record MonsterSpawnEvent(MapView.MonsterView view, boolean newSpawn, int effect, boolean fake) implements ServerEvent {
}
