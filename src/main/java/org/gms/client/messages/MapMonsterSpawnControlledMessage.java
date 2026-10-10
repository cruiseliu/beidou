package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;

/**
 * map → player：受控落地（测试版融合帧）——该连接是此怪的 controller，落地即授控，
 * 只发 MONSTER_SPAWN_CONTROL 全身帧（不发 MONSTER_SPAWN；与
 * {@link MapMonsterSpawnMessage} 按 controller 身份分流）。载荷 = {@link MapView.MonsterView}
 * 值快照（map 域 post 时点冻结）。mapId 供接收方校验自身所在图。
 */
public record MapMonsterSpawnControlledMessage(int mapId, MapView.MonsterView view) implements ActorMessage {
}
