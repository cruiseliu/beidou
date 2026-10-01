package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;
import org.gms.remote.modules.map.client.MonsterMove;

/** 某怪物移动了（他人流中继，接收方连接视角的语义投递）。 */
public record MonsterMoveEvent(MonsterMove move) implements ServerEvent {
}
