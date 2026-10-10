package org.gms.remote.modules.map.server;

import org.gms.client.character.MapView;
import org.gms.remote.ServerEvent;

/**
 * 受控落地（S→C 语义事件）：MONSTER_SPAWN_CONTROL 全身帧（mode 1），无独立落地帧。
 * 载荷 = {@link MapView.MonsterView} 值快照，freeze 物化帧。
 */
public record MonsterSpawnControlledEvent(MapView.MonsterView view) implements ServerEvent {
}
