package org.gms.model.pojo;

import org.gms.client.Skill;

public class SkillEntry {
    /** 永久技能（无到期时刻）的 expiration 值 */
    public static final long PERMANENT = -1;

    /** 所属技能（与 entries 的 skillId 键一致） */
    public final Skill skill;
    public int masterLevel;
    public int skillLevel;
    public long expiration;

    public SkillEntry(Skill skill, int skillLevel, int masterLevel, long expiration) {
        this.skill = skill;
        this.skillLevel = skillLevel;
        this.masterLevel = masterLevel;
        this.expiration = expiration;
    }

    public int getSkillId() {
        return skill.getId();
    }

    @Override
    public String toString() {
        return skillLevel + ":" + masterLevel;
    }
}
