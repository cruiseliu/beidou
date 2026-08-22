package org.gms.client.character;

import org.gms.client.Stat;

/**
 * 属性下标（Java enum 不能当整型用，故用常量类）。
 * CharacterStats 的 base/local/equip 数组与 StatsUpdate.base 共用此下标；
 * KEYS 为下标 → 封包 Stat 位的映射，两者顺序一致，此处为唯一真相。
 * <p>
 * 基础数组含 HP/MP 最大值（MAX_HP/MAX_MP）与攻击力（P_ATK/M_ATK）槽位，供快照化统一复制；
 * P_ATK/M_ATK 无封包位，不进 KEYS；
 * 只遍历力敏智运的循环请用 [BASE_STAT_BEGIN, BASE_STAT_END) 区间。
 */
public final class StatIndex {
    public static final int STR = 0;
    public static final int DEX = 1;
    public static final int INT = 2;
    public static final int LUK = 3;
    public static final int MAX_HP = 4;
    public static final int MAX_MP = 5;
    public static final int P_ATK = 6;   // 物理攻击力
    public static final int M_ATK = 7;   // 魔法攻击力
    public static final int STAT_COUNT = 8;

    /** 只遍历力敏智运（基础四维）的循环起点/终点（开区间） */
    public static final int BASE_STAT_BEGIN = 0;
    public static final int BASE_STAT_END = 4;

    /** 下标 → 封包 Stat 位 */
    public static final Stat[] KEYS = {
        Stat.STR,
        Stat.DEX,
        Stat.INT,
        Stat.LUK,
        Stat.MAXHP,
        Stat.MAXMP
    };

    private StatIndex() { }
}
