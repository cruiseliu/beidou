package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;

/**
 * map → player：受控落地（双帧回退版）——该连接是此怪的 controller，先后收
 * MONSTER_SPAWN（落地注册帧）+ MONSTER_SPAWN_CONTROL（授控帧）。测试版单融合帧
 * （仅 CONTROL）在真端验证不可命中（客户端不把 control-only 注册的怪挂进攻击目标池），
 * 恢复 legacy 双帧形态。载荷 = {@link MapView.MonsterView} 值快照 + 落地会话事实。
 */
public record MapMonsterSpawnControlledMessage(
    int mapId,
    MapView.MonsterView monster,
    boolean newSpawn
) implements ActorMessage {}
