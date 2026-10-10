package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.server.packets.V83Packet;

/**
 * freeze 的产物：怪物落地帧在入域时点物化（mob 活状态 → MonsterBlock 变体帧；
 * 普通落地 = SPAWN_MONSTER，假怪 = CONTROL 头 kind 5）。
 */
public record FrozenMonsterSpawnEvent(V83Packet packet) implements ServerEventBase {
}
