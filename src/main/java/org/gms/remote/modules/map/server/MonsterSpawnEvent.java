package org.gms.remote.modules.map.server;

import org.gms.client.character.MonsterView;
import org.gms.remote.ServerEvent;

/**
 * 怪物落地（S→C 语义事件）：帧形态（普通落地/淡入/特演/假怪）归版本实现。
 * 载荷 = {@link MonsterView} 值快照（无活引用；fake 也是快照字段）+ 落地会话事实。
 *
 * <p>{@code controller} = 本接收方是此怪的 controller（落地即授控）。版本实现据此决定
 * wire 形状：单 SPAWN_MONSTER，或 SPAWN_MONSTER + SPAWN_MONSTER_CONTROL（客户端 constraint
 * 是版本私事，不回到 gameplay）。
 */
public record MonsterSpawnEvent(
    MonsterView view,
    boolean newSpawn,
    int effect,
    boolean controller
) implements ServerEvent {}
