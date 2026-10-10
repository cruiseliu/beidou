package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;

/**
 * map → player：授控通知（原 aggro-control legacy 桥的值化）。载荷 = map object id +
 * 授控意图——帧数据由接收方从自己的 map view（{@link MapView#monster}）取
 * {@link MapView.MonsterView} 值快照（落地/入场 placement 时点登记）。
 * mapId 供接收方校验自身所在图。
 */
public record MapControlMonsterMessage(int mapId, int oid, boolean aggro) implements ActorMessage {
}
