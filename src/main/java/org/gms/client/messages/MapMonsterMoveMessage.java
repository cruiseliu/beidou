package org.gms.client.messages;

import org.gms.infra.ActorMessage;
import org.gms.remote.modules.map.client.MonsterMove;

/**
 * map → player：视野内某怪物移动了（他人流中继投递）。载荷为服务端裁决后的合成
 * 快照（{@link MonsterMove}，构造后只读）。
 */
public record MapMonsterMoveMessage(MonsterMove move) implements ActorMessage {
}
