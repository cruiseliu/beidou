package org.gms.client.character;

import org.gms.client.Stat;

/**
 * 四维下标（Java enum 不能当整型用，故用常量类）。
 * CharacterStats 的 base/local/equip 数组与 StatsUpdate.base 共用此下标；
 * KEYS 为下标 → 封包 Stat 位的映射，两者顺序一致，此处为唯一真相。
 */
public final class StatIndex {
    public static final int STR = 0;
    public static final int DEX = 1;
    public static final int INT = 2;
    public static final int LUK = 3;
    public static final int BASE_STAT_COUNT = 4;

    /** 下标 → 封包 Stat 位 */
    public static final Stat[] KEYS = {
        Stat.STR,
        Stat.DEX,
        Stat.INT,
        Stat.LUK
    };

    private StatIndex() { }
}
