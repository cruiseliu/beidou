package org.gms.client.job;

import org.gms.client.inventory.WeaponType;

import java.util.List;
import java.util.Map;

/**
 * 职业自我描述（不可变；每个职业在 JobRegistry 显式登记一份，零 id 推断）。
 *
 * 语义约定：
 * - 阵营/风格是显式机制，游戏内容以白名单引用，不做数值推断。
 * - 身份一律用职业 id（int）表达；JobEnum 是现版本做特判用的遗留类型，
 *   重构后理论上用不到，需要暂时使用的地方经 JobEnum.getById(id) 转换。
 * - advancementCount = 本职业处于第几转（技能书分页深度，0 起）。
 * - advancementLevel = 转成【本职业】所需等级（"战士多少级转职"是战士自己的信息，不写入后续职业；
 *   魔法师系为 8、其余系为 10 等差异由各职业显式登记，不做"同系同等级"推断）。
 * - advancementHint = 本职业"该去转职了"的建议等级（hint，官方不参与实际逻辑；
 *   仅 use_enforce_job_level_range 服务器配置读取，用于截断经验）。它是当前职业自己的属性，
 *   与"下一转"无关（转职是分支结构），最终职业 hint = maxLevel（等效不截断）。
 * - maxLevel = 当前职业自身的最高等级，与转职链正交——新手同样可升到 200 级；
 *   逐职业显式登记（Cygnus 系登记 120、冒险家系登记 200 等），**禁止按阵营/风格推断上限**。
 * - 本定义**不存在"下一转"概念**：转职是分支结构（新手可转战士/魔法师/弓手/飞侠/海盗任意一系），
 *   职业只声明 previousJob（回溯链），转职路由由转职任务/脚本决定；
 *   "到达转职等级停止获取经验"是服务器设置（use_enforce_job_level_range），与职业定义无关。
 * - 转职分支（如海盗→拳手/枪手）是隐式机制，不建模；由 JobRegistry 逐条登记区分。
 * - master level 是技能自身数据（Skill 从 wz 读取，缺省语义 = max level），职业定义不存、不推断。
 * - acquiredSkills = 转职时"获得"的技能白名单（每职业约 10 个）；获得 = 可分配 SP 的隐藏态，与 level/master 正交。
 * - 武器规则：默认在 WeaponType（后续补 defaultStatRule），本职业 weaponStatRules 只写覆盖特例（按职业粒度，非 style）。
 * - spChainShared = 本职业的 SP 是否与转职链【后续】职业（含自身）共用（Evan 跨阶段共用 = true）。
 *   分配时只允许：spChainShared=true 的职业把 SP 花在本职业及转职链后续职业上；
 *   永远不能花在转职链前驱上（否则玩家会转职前攒 SP 白嫖），也永远不能合法获得不在转职链上的职业 SP。
 */
public record JobDefinition(
        int jobId,                      // 本职业 id（唯一键，登记/回溯用）
        JobFaction faction,
        JobStyle style,

        int advancementCount,
        int advancementLevel,
        int advancementHint,
        int maxLevel,
        int previousJobId,              // 转职链上一职业 id；新手 = -1

        // ── 升级/转职授予（升级与转职对称：都是 gainStats，升级挂在区间上、转职一次性一份） ──
        List<LevelUpRange> levelUp,             // 升级区间（from exclusive / to inclusive）+ 该区间每级的 gainStats
        GainStats advancementGainStats,         // 转职一次性授予（null = 无授予；maxHp/maxMp/ap/sp 全含）

        // ── SP（跨职业共用标记） ──
        boolean spChainShared,      // true = SP 与转职链后续职业（含自身）共用；false = 仅本职业

        // ── 技能（转职获得白名单；master 归技能数据） ──
        List<Integer> acquiredSkills,

        // ── AP 分配器（脚本导出 map 的 key：export default { beginner: beginnerAutoAssign, ... }） ──
        String apAutoAssignKey,
        boolean forceAutoAssignAp,

        // ── 武器规则（按职业覆盖；默认在 WeaponType） ──
        Map<WeaponType, StatRule> weaponStatRules,

        // ── 机制装配（modifier 链 + listener；JSON 中为名字，注册时解析成实例） ──
        List<String> modifiers,
        List<String> listeners
) {
    /**
     * 指定等级对应的升级奖励（from exclusive / to inclusive 区间命中）；未命中返回 null。
     * 命中顺序按列表顺序，第一个满足 from &lt; level &lt;= to 的区间生效。
     */
    public GainStats gainStatsAtLevel(int level) {
        for (LevelUpRange range : levelUp) {
            if (level > range.from() && level <= range.to()) {
                return range.gainStats();
            }
        }
        return null;
    }
}
