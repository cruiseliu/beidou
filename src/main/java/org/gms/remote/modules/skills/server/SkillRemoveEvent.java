package org.gms.remote.modules.skills.server;

import org.gms.remote.ServerEvent;

/** 客户端侧删除已获得技能（原 SemanticEvent.SkillRemove）。 */
public record SkillRemoveEvent(int skillId) implements ServerEvent {
}
