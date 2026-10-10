package org.gms.client.messages;

import org.gms.infra.ActorMessage;

/**
 * map → player：收回怪物控制（原 aggro-stop legacy 桥的值化；与
 * {@link MapControlMonsterMessage} 成对）。载荷 = map object id——帧（CONTROL 0x00 + oid）
 * 归版本实现。无视野门：legacy stop 不检查 controller 视野（出视野怪的控制态同样收回）。
 * mapId 供接收方校验自身所在图。
 */
public record MapStopControlMonsterMessage(int mapId, int oid) implements ActorMessage {
}
