package org.gms.remote.modules.cooldown.server;

import org.gms.remote.ServerEvent;

/** 清除技能冷却显示（到期/重置；原 SemanticEvent.CooldownClear）。 */
public record CooldownClearEvent(int skillId) implements ServerEvent {
}
