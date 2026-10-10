package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.server.packets.ControlMonsterPacket;

/**
 * freeze 的产物：授控全身帧在入域时点物化（mob 活状态 → 纯字段帧，
 * 含 stati 位掩码/条目序与父怪关联判定）。
 */
public record FrozenControlMonsterEvent(ControlMonsterPacket packet) implements ServerEventBase {
}
