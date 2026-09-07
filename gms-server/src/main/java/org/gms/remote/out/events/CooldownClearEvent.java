package org.gms.remote.out.events;

/** 清除技能冷却显示（到期/重置；原 SemanticEvent.CooldownClear）。 */
public record CooldownClearEvent(int skillId) implements SemanticEvent {
}
