package org.gms.client.messages;

import org.gms.client.character.MonsterView;
import org.gms.infra.ActorMessage;

/**
 * map → player：怪物落地。载荷 = {@link MonsterView} 值快照（map 域 post 时点冻结）
 * + 落地会话事实（newSpawn/effect）；visible 判定与 MapView 登记所需的对象条目由
 * 接收方从快照派生（type=MONSTER / mobId / position）。mapId 供接收方校验自身所在图。
 */
public record MapMonsterSpawnMessage(
    int mapId,
    MonsterView monster,
    boolean newSpawn,
    int effect
) implements ActorMessage {}
