package org.gms.client.job;

import org.gms.client.character.Stat;

import java.util.List;

/**
 * 武器属性规则：主属性列表 + 副属性列表（各元素权重 1，求和）。
 *
 * primary 允许多个——后续版本存在多个主属性的职业（当前版本均为 1 个）。
 * 伤害计算：mainstat = Σ attrs[primary]；secondarystat = Σ attrs[secondary]。
 *
 * 默认规则已数据化在武器类型定义（data/weapon_type/*.json 的 statRule 字段），
 * 本类仅作为职业级覆盖（JobDefinition.weaponStatRules）的规则载体。
 */
public record StatRule(List<Stat> primary, List<Stat> secondary) {
}
