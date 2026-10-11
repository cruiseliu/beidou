package org.gms.remote.modules.map.server;

import org.gms.client.character.MapView;
import org.gms.remote.ServerEvent;

/**
 * 受控落地（S→C 语义事件，双帧）：MONSTER_SPAWN 注册帧 + MONSTER_SPAWN_CONTROL 授控帧。
 * 载荷 = {@link MapView.MonsterView} 值快照，freeze 物化双帧。
 */
public record MonsterSpawnControlledEvent(
    MapView.MonsterView view,
    boolean newSpawn
) implements ServerEvent {}
