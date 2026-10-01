package org.gms.client.job;

import org.gms.client.weaponType.WeaponTypeDefinition.ActionRules;

/**
 * 职业级武器规则覆盖（JobDefinition.weaponStatRules 的值）。
 *
 * 覆盖武器类型定义（data/weapon_type/*.json）中的：
 * - statRule：主/副属性（必填）
 * - actions：各攻击动作系数（可选；null = 沿用武器类型默认）
 *
 * 例：盗贼用匕首（DAGGER）覆盖为 LUK 主/DEX+STR 副、系数 3.6：
 *   { "statRule": { "primary": ["LUK"], "secondary": ["DEX", "STR"] },
 *     "actions":  { "swing": 3.6, "stab": 3.6, "shoot": 0 } }
 */
public record WeaponRule(StatRule statRule, ActionRules actions) {

    public WeaponRule {
        actions = actions == null ? null : actions;
    }

    public static WeaponRule of(StatRule statRule, ActionRules actions) {
        return new WeaponRule(statRule, actions);
    }

    public static WeaponRule of(StatRule statRule) {
        return new WeaponRule(statRule, null);
    }
}
