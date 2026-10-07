package org.gms.client.messages;

import org.gms.infra.ActorMessage;

/**
 * map → player：视野内某怪物 HP 变化了（伤害管线投递，原 SHOW_MONSTER_HP 本体直发/
 * postLegacyPacket 桥形态的语义化）。载荷为服务端裁决后的显示值（百分比已算好）。
 */
public record MapMonsterHpMessage(int oid, int hpPercent) implements ActorMessage {
}
