package org.gms.client.job;

import org.gms.client.PacketStat;

import java.util.List;

/**
 * 武器属性规则：主属性列表 + 副属性列表（各元素权重 1，求和）。
 *
 * primary 允许多个——后续版本存在多个主属性的职业（当前版本均为 1 个）。
 * 伤害计算：mainstat = Σ attrs[primary]；secondarystat = Σ attrs[secondary]。
 *
 * 例：
 * 弓   → primary=[DEX], secondary=[STR]
 * 短刀 → primary=[LUK], secondary=[DEX, STR]   （飞侠主 LUK，副 DEX+STR）
 * 剑   → primary=[STR], secondary=[DEX]
 */
public record StatRule(List<PacketStat> primary, List<PacketStat> secondary) {

    public static final StatRule BOW = new StatRule(List.of(PacketStat.DEX), List.of(PacketStat.STR));
    public static final StatRule THIEF_WEAPON = new StatRule(List.of(PacketStat.LUK), List.of(PacketStat.DEX, PacketStat.STR));
    public static final StatRule MELEE = new StatRule(List.of(PacketStat.STR), List.of(PacketStat.DEX));

    public static StatRule of(PacketStat primary, PacketStat... secondary) {
        return new StatRule(List.of(primary), List.of(secondary));
    }
}
