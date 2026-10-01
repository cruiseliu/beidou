package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/** 某角色移动了（他人流中继，接收方连接视角的语义投递）。原 relayMoveMonster 族同型的 S→C 事件化。 */
public record CharacterMoveEvent(int charId, List<MoveElement> movements) implements ServerEvent {
}
