package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;
import org.gms.server.life.Monster;

/**
 * map → player：怪物落地（原 spawnAndPostMapObject 预构建包的值化；与
 * {@link MapControlMonsterMessage} 同族——授控/落地同一套值化路径）。
 *
 * <p>载荷含 {@link Monster} 活引用（值化纪律的显式豁免，理由同授控消息：strict 管线内
 * 禁反查 map 本体；freeze 快照读点取代 legacy 预构建读点，快照时点从 post 前移到
 * dispatch——mob 落地后首动前窗口内的位置以 freeze 读点为准）。
 * viewEntry 携 post 时点位置快照，供 visible 判定与 MapView 登记。
 * mapId 供接收方校验自身所在图。
 */
public record MapMonsterSpawnMessage(int mapId, MapView.Entry viewEntry, boolean newSpawn, int effect,
                                     boolean fake, Monster mob) implements ActorMessage {
}
