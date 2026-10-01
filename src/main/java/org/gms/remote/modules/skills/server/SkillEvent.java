package org.gms.remote.modules.skills.server;

import org.gms.remote.ServerEvent;

/** 技能学习/更新（原 SemanticEvent.Skill）。 */
public record SkillEvent(SkillUpdate update) implements ServerEvent {
}
