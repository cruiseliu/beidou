package org.gms.client.character;

/**
 * 语义层 stat：角色面板属性（力敏智运、max hp/mp、p/m atk）。
 * enum 即数组槽位：ordinal() 为 base/total 数组下标，声明顺序 = 数组布局，
 * 力敏智运（SDIL）必须连续排在头部——SDIL_INDEX_BEGIN/END 依赖此顺序，除此之外不感知属性排序。
 * 面板属性 = 装备/buff 可附加的角色面板部分；HP/MP（临时状态）与 AP（资源）不在此列。
 *
 * <p>同时作为 RemoteClient 隔离层 updateStats 的语义字段（wire 位值/宽度是版本编码器的私事，
 * P_ATK/M_ATK 无 wire 位由实现层丢弃）；迁移期 PacketStat→Stat 的映射在 CharacterStats 桥接。
 */
public enum Stat {
    STR, DEX, INT, LUK,
    MAX_HP, MAX_MP,
    P_ATK,   // 物理攻击力（攻击力存储，无封包位）
    M_ATK;   // 魔法攻击力

    /** 力敏智运下标区间（SDIL = STR/DEX/INT/LUK）：仅遍历基础四维的循环边界 */
    static final int SDIL_INDEX_BEGIN = STR.ordinal();
    static final int SDIL_INDEX_END = LUK.ordinal() + 1;

    /** 数组槽位总数 */
    public static int count() {
        return values().length;
    }
}
