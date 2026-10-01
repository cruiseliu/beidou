package org.gms.remote.modules.map.client;

import org.gms.remote.modules.map.client.movement.MoveElement;

import java.awt.*;
import java.util.List;

/**
 * MOVE_LIFE 他人流中继的合成载荷（与 {@link MoveLife} 对偶：MoveLife 是 controller 上报，
 * MonsterMove 是服务端校验/掷骰后向他人转发的合成结果）。字段即服务端裁决值
 * （rawActivity 可能被攻击门控改写、skillId/level 为预掷下拍技能）；elements 沿用
 * controller 上报的语义快照（构造后只读）。
 */
public record MonsterMove(int oid, boolean skillPossible, int skill, int skillId, int skillLevel,
                          int pOption, Point startPos, List<MoveElement> elements) {
}
