package org.gms.model.pojo;

public class SkillEntry {
    public int masterLevel;
    public int skillLevel;
    public long expiration;

    public SkillEntry(int skillLevel, int masterLevel, long expiration) {
        this.skillLevel = skillLevel;
        this.masterLevel = masterLevel;
        this.expiration = expiration;
    }

    @Override
    public String toString() {
        return skillLevel + ":" + masterLevel;
    }
}
