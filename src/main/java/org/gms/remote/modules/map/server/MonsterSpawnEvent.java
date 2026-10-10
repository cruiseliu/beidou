package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;
import org.gms.server.life.Monster;

/**
 * 怪物落地（S→C 语义事件）：帧形态（普通落地/淡入/特演/假怪）归版本实现。
 * 携带 mob 活引用，版本 route 在 freeze 时点物化帧（快照在入域时点抽取）。
 */
public record MonsterSpawnEvent(Monster mob, boolean newSpawn, int effect, boolean fake) implements ServerEvent {
}
