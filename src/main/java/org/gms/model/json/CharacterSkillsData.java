package org.gms.model.json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CharacterSkills 的持久化数据载体（character_json 的 skills 域）。
 * 技能等级与技能 CD 分两节存放，key 为 skillId。纯 public 字段，由 fastjson2 序列化/反序列化。
 */
public class CharacterSkillsData {
    public Map<Integer, SkillEntryData> entries = new LinkedHashMap<>();
    public Map<Integer, CooldownData> cooldowns = new LinkedHashMap<>();

    public static class SkillEntryData {
        public int level, masterLevel;
        /** null = 永久（对应 SkillEntry.expiration 的 -1） */
        public Long expiration;
    }

    public static class CooldownData {
        public long startTime, length;
    }
}
