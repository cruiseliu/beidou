package org.gms.model.json;

import java.util.List;

/**
 * CharacterDebuffs 的持久化数据载体（character_json 的 debuffs 域）。
 * 列表项原样保存 DebuffStatus 的 startTime/length；空表用 null（无 debuff 不落库）。
 * 反序列化时 startTime 经 CharacterData.timestamp 推移到当前时刻。
 * 纯 public 字段，由 fastjson2 序列化/反序列化。
 */
public class CharacterDebuffsData {
    /** null = 无 debuff；否则逐项保存 */
    public List<DebuffEntryData> debuffs;

    public static class DebuffEntryData {
        /** debuff 类型（Disease.ordinal()） */
        public int debuff;
        /** 来源技能类型 id（MobSkillType.getId()） */
        public int mobSkillType;
        /** 来源技能等级 */
        public int mobSkillLevel;
        /** 生效时刻（ms），与 DebuffStatus.startTime 一致（保存时点） */
        public long startTime;
        /** 时长（ms），与 DebuffStatus.length 一致 */
        public long length;
    }
}
