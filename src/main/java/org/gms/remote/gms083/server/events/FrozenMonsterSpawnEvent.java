package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.server.packets.V83Packet;

/**
 * freeze 的产物：怪物落地 wire 帧组在入域时点物化（mob 活状态 → MonsterBlock 变体帧）。
 *
 * <p>{@code spawn} 恒存在（普通落地 = SPAWN_MONSTER，假怪 = CONTROL 头 kind 5）；
 * {@code control} 仅在“本接收方是 controller”时附加（SPAWN_MONSTER_CONTROL 授控帧）。
 * 客户端要求先跑 SPAWN_MONSTER 才能完成创建、CONTROL-only 创建不完整——这是 v83 的
 * wire 约束，拆分/顺序全部收在这里，gameplay 只发一个语义事件。
 */
public record FrozenMonsterSpawnEvent(V83Packet spawn, V83Packet control) implements ServerEventBase {
}
