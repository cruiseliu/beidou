package org.gms.remote.out.events;

/** 客户端侧删除已获得技能（原 SemanticEvent.SkillRemove）。 */
public record SkillRemoveEvent(int skillId) implements SemanticEvent {
}
