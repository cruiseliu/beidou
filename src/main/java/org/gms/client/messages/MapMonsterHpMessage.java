package org.gms.client.messages;

import org.gms.infra.ActorMessage;

/**
 * map → player：视野内某怪物 HP 变化了（伤害管线投递，原 SHOW_MONSTER_HP 本体直发/
 * postLegacyPacket 桥形态的语义化）。载荷为服务端裁决后的显示值（百分比已算好）。
 * mapId 供接收方 actor 校验自身所在图（异步边界：map 的玩家表是陈旧快照，投递解析
 * 会把消息送给已切图者，由唯一知道"当前时点"的 Player actor 丢弃）。
 */
public record MapMonsterHpMessage(int mapId, int oid, int hpPercent) implements ActorMessage {
}
