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
    // ── 角色段 [0, EQUIP_INDEX_BEGIN)：装备同名属性直接聚合进角色 total（同一语义）──
    STR, DEX, INT, LUK,
    MAX_HP, MAX_MP,
    P_ATK,   // 物理攻击力（装备 watk）
    M_ATK,   // 魔法攻击力（装备 matk）

    // ── 装备段 [EQUIP_INDEX_BEGIN, EQUIP_INDEX_END)：Equip.stats 数组布局，角色 total 不聚合 ──
    P_DEF,   // 物理防御（装备 wdef）
    M_DEF,   // 魔法防御（装备 mdef）
    ACCURACY,     // 命中
    AVOIDABILITY,   // 回避
    SPEED,   // 移动速度
    JUMP,    // 跳跃力
    HANDS;   // 手技

    /** 力敏智运下标区间（SDIL = STR/DEX/INT/LUK）：仅遍历基础四维的循环边界 */
    static final int SDIL_INDEX_BEGIN = STR.ordinal();
    static final int SDIL_INDEX_END = LUK.ordinal() + 1;
    /** 装备独有属性段（防/命/回/手/速/跳）；角色段即 [0, EQUIP_INDEX_BEGIN) */
    static final int EQUIP_INDEX_BEGIN = P_DEF.ordinal();
    static final int EQUIP_INDEX_END = JUMP.ordinal() + 1;

    /** 数组槽位总数 */
    public static int count() {
        return values().length;
    }
}
