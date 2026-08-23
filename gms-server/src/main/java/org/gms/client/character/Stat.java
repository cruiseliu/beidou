package org.gms.client.character;

import org.gms.client.PacketStat;

/**
 * 面板属性下标（enum 即数组槽位：ordinal() 为 base/total 数组下标，声明顺序 = 数组布局，
 * 力敏智运（SDIL）必须连续排在头部——SDIL_INDEX_BEGIN/END 依赖此顺序，除此之外不感知属性排序）。
 * 面板属性 = 装备/buff 可附加的角色面板部分；HP/MP（临时状态）与 AP（资源）不在此列。
 */
public enum Stat {
    STR(PacketStat.STR),
    DEX(PacketStat.DEX),
    INT(PacketStat.INT),
    LUK(PacketStat.LUK),
    MAX_HP(PacketStat.MAXHP),
    MAX_MP(PacketStat.MAXMP),
    P_ATK(null),   // 物理攻击力（攻击力存储，无封包位）
    M_ATK(null);   // 魔法攻击力

    /** 力敏智运下标区间（SDIL = STR/DEX/INT/LUK）：仅遍历基础四维的循环边界 */
    static final int SDIL_INDEX_BEGIN = STR.ordinal();
    static final int SDIL_INDEX_END = LUK.ordinal() + 1;

    private final PacketStat packet;

    Stat(PacketStat packet) {
        this.packet = packet;
    }

    /** 封包位（P_ATK/M_ATK 无封包位为 null） */
    public PacketStat packet() {
        return packet;
    }

    /** 数组槽位总数 */
    public static int count() {
        return values().length;
    }
}
