package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

/**
 * 怪物死亡场景事件（S→C 语义通知）：接收方连接视角的死亡演出投递。
 * 演出形态（消失/淡出/特演）由版本实现解释；结算（经验/任务计数/家族声望）
 * 不在此，归 gameplay 域的击杀结算链。
 */
public record MonsterKilledEvent(int oid, int animation) implements ServerEvent {
}
