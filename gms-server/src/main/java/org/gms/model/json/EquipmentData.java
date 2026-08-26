package org.gms.model.json;

import org.gms.client.character.Stat;

/**
 * Equip（装备域）的持久化数据载体（character_json 的 inventory 域内嵌）。
 * 属性按 Stat 枚举拆为显式字段（0 序列化为 null）；ringId=-1、其余数值字段 0 也序列化为 null。
 * officialCanLevelUp 为构造期派生（wz 成长表判定），不序列化。
 */
public class EquipmentData {
    public Integer enhancementSlots;
    public Integer enhancementLevel;
    public Integer itemLevel;
    public Integer itemExp;
    public Integer vicious;
    /** 戒指 id；-1（非戒指）序列化为 null */
    public Integer ringId;

    // ── 属性（Stat 枚举对齐；0 序列化为 null）──
    public Integer str;
    public Integer dex;
    public Integer int_;
    public Integer luk;
    public Integer maxHp;
    public Integer maxMp;
    public Integer pAtk;
    public Integer mAtk;
    public Integer pDef;
    public Integer mDef;
    public Integer accuracy;
    public Integer avoidability;
    public Integer hands;
    public Integer speed;
    public Integer jump;

    public Integer stat(Stat stat) {
        return switch (stat) {
            case STR -> str;
            case DEX -> dex;
            case INT -> int_;
            case LUK -> luk;
            case MAX_HP -> maxHp;
            case MAX_MP -> maxMp;
            case P_ATK -> pAtk;
            case M_ATK -> mAtk;
            case P_DEF -> pDef;
            case M_DEF -> mDef;
            case ACCURACY -> accuracy;
            case AVOIDABILITY -> avoidability;
            case HANDS -> hands;
            case SPEED -> speed;
            case JUMP -> jump;
        };
    }

    public void stat(Stat stat, Integer value) {
        switch (stat) {
            case STR -> str = value;
            case DEX -> dex = value;
            case INT -> int_ = value;
            case LUK -> luk = value;
            case MAX_HP -> maxHp = value;
            case MAX_MP -> maxMp = value;
            case P_ATK -> pAtk = value;
            case M_ATK -> mAtk = value;
            case P_DEF -> pDef = value;
            case M_DEF -> mDef = value;
            case ACCURACY -> accuracy = value;
            case AVOIDABILITY -> avoidability = value;
            case HANDS -> hands = value;
            case SPEED -> speed = value;
            case JUMP -> jump = value;
        };
    }
}
