package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

/** mob 移动 ack（controller 连接回执：配对 moveid + MP/仇恨/下拍技能指令）。 */
public record AckMoveMonsterEvent(int oid, short moveid, int currentMp, boolean useSkills,
                                  int skillId, int skillLevel) implements ServerEvent {
}
