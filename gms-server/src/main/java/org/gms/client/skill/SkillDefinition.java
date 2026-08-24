package org.gms.client.skill;

/**
 * 技能定义补充（数据驱动，data/skill/*.json）。
 *
 * 与 job/weapon type 不同，技能的主要数据源是 wz 文件（Skill.wz），
 * 本 definition 只按需补充 wz 缺失/未建模的信息。
 *
 * 初版仅补充"升级时提升 HP/MP 上限"信息（用于消除升级/转职时
 * 先判断职业再判断技能的硬编码特判）：
 * <pre>
 *   { "skillId": 1000001,
 *     "passive": { "increaseMaxHpOnLevelUp": "x",
 *                  "increaseMaxMpOnLevelUp": "y" } }
 * </pre>
 * 其中字段值 = 该数值在 wz effect 中的字段名（如 "x"/"y"/"mp"），
 * 运行期经 BuffEffectData 按名取值。没有需要补充信息的技能不创建 JSON 文件；
 * 为 null 的字段在 JSON 中不写出。
 */
public record SkillDefinition(
        int skillId,        // 技能 id（Skill.wz 的键）
        Passive passive     // 被动信息（升级加成等；无则 null）
) {
    /**
     * 被动信息：升级/转职时提升的 HP/MP 上限。
     * 字段值 = wz effect 中的字段名（如 "x"/"y"/"mp"）；无对应加成则 null。
     */
    public record Passive(
            String increaseMaxHpOnLevelUp,   // 升级时提升 MAX_HP 的 wz 字段名（无则 null）
            String increaseMaxMpOnLevelUp    // 升级时提升 MAX_MP 的 wz 字段名（无则 null）
    ) {
    }
}
