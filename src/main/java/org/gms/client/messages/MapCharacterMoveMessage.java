package org.gms.client.messages;

import org.gms.infra.ActorMessage;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/**
 * map → player：视野内某角色移动了（他人流中继投递，原 broadcastCharacterMove 的
 * 跨线程直发改为本消息的接收方 strand 投递）。载荷：铸造图 id（接收方权威校验）+
 * source 身份 id + 语义元素快照（构造期拷贝冻结，全体接收方共享同一不可变列表）。
 */
public record MapCharacterMoveMessage(int mapId, int charId, List<MoveElement> movements) implements ActorMessage {

    public MapCharacterMoveMessage {
        movements = List.copyOf(movements);
    }
}
