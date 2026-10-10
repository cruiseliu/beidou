package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;

/**
 * map → player：怪物落地（原 spawnAndPostMapObject 预构建包的值化；与
 * {@link MapControlMonsterMessage} 同族——授控/落地同一套值化路径）。
 *
 * <p>载荷 = {@link MapView.MonsterView} 值快照（map 域 post 时点冻结，读点 = legacy 预构建
 * 构建点）+ 落地会话事实（newSpawn/effect/fake）。viewEntry 携 post 时点位置快照，
 * 供 visible 判定与 MapView 登记。mapId 供接收方校验自身所在图。
 */
public record MapMonsterSpawnMessage(int mapId, MapView.Entry viewEntry, boolean newSpawn, int effect,
                                     boolean fake, MapView.MonsterView view) implements ActorMessage {
}
