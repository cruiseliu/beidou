package org.gms.remote.out.events;

import org.gms.remote.SkillUpdate;

/** 技能学习/更新（原 SemanticEvent.Skill）。 */
public record SkillEvent(SkillUpdate update) implements SemanticEvent {
}
