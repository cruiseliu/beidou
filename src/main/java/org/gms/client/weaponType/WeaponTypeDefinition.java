package org.gms.client.weaponType;

import org.gms.client.job.StatRule;

/**
 * 武器类型定义（数据驱动，data/weapon_type/*.json）。
 *
 * 常规武器类型记录三类信息：
 * 1. itemIdRange：物品 id 范围（避免用 id 数字推算逻辑判断武器种类）
 * 2. actions：攻击动作 → 伤害系数（砍/刺/射，见 {@link WeaponAction}）
 * 3. statRule：主/副属性列表（伤害公式的 Primary/Secondary）
 * 4. ammoIdRange：弹药物品 id 范围（弓→箭矢、弩→弩矢、拳套→飞镖、短枪→子弹；无弹药武器为 null）
 * 5. twoHanded：是否双手武器（双手武器不能与副手/盾牌同时装备）
 * 6. priority：查找优先级（itemIdRange 重叠时返回 priority 最高者；默认 0）
 *
 * 徒手（无武器）暂不在此定义——保留现有 job 特判，将来作为伪技能处理。
 */
public record WeaponTypeDefinition(
        WeaponTypeEnum type,            // 武器类型枚举
        ItemIdRange itemIdRange,        // 匹配的物品 id 范围（含端点）
        ActionRules actions,            // 各攻击动作的伤害系数（砍/刺/射）
        StatRule statRule,              // 主/副属性规则
        ItemIdRange ammoIdRange,        // 弹药物品 id 范围（无弹药武器为 null）
        boolean twoHanded,              // 是否双手武器
        int priority                    // 查找优先级（itemIdRange 重叠时返回最高者；默认 0）
) {
    /**
     * 物品 id 范围（含端点）：用于判断 itemId 属于哪种武器，替代 getWeaponType 的数字推算。
     */
    public record ItemIdRange(int from, int to) {
        public boolean contains(int itemId) {
            return itemId >= from && itemId <= to;
        }
    }

    /**
     * 攻击动作 → 伤害系数。未声明的动作不适用（系数 0）。
     */
    public record ActionRules(double swing, double stab, double shoot) {
        public double multiplier(WeaponAction action) {
            return switch (action) {
                case SWING -> swing;
                case STAB -> stab;
                case SHOOT -> shoot;
            };
        }
    }

    /** 该武器类型是否匹配指定 itemId */
    public boolean matches(int itemId) {
        return itemIdRange.contains(itemId);
    }

    /** 该武器类型是否使用指定弹药（无弹药武器恒 false） */
    public boolean usesAmmo(int itemId) {
        return ammoIdRange != null && ammoIdRange.contains(itemId);
    }
}
