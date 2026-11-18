package org.gms.remote.modules.map.server;

import org.gms.client.character.MonsterView;
import org.gms.remote.ServerEvent;

/**
 * 怪物落地（S→C 语义事件）：帧形态（普通落地/淡入/特演/假怪）归版本实现。
 * 载荷 = {@link MonsterView} 值快照（无活引用；fake 也是快照字段），版本 route 在
 * freeze 时点物化帧。
 */
public record MonsterSpawnEvent(MonsterView view, boolean newSpawn, int effect) implements ServerEvent {
}
