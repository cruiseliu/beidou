package org.gms.remote.modules.skills.server;

import org.gms.remote.ServerEvent;

/** 清除技能冷却显示（到期/重置；原 cooldown 域并入 skills）。 */
public record CooldownClearEvent(int skillId) implements ServerEvent {
}
