package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

/** 怪物 HP 变化（S→C 语义事件）：oid + 显示百分比（translate 层换算完毕，packet 字段直排）。 */
public record UpdateMonsterHpEvent(int oid, int hpPercent) implements ServerEvent {
}
