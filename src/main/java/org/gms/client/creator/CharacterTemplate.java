package org.gms.client.creator;

import org.gms.model.json.CharacterData;
import org.gms.model.json.CharacterStatsData;
import org.gms.model.json.ItemData;

import java.util.List;
import java.util.Map;

/**
 * 角色创建模板（新角色的唯一数据真相；对齐 JobDefinition/ItemDefinition 的数据驱动模式）。
 * Java 侧只有创建逻辑（校验/装配/持久化），一切数据（等级/职业/出生图/属性/装备/物品/技能/
 * 候选集位置/入口职业码）在 data/character_template/*.json。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code novice} = 可用作新手角色（选角界面新建）；老兵卡模板为 false。改允许创建的
 *       职业直接改模板本字段（原 GameConfig 职业开关已废弃）。</li>
 *   <li>{@code noviceJobCode} = 选角界面的客户端职业码（0 骑士团/1 冒险家/2 战神）；
 *       {@code mapleLifeJobCode} = 老兵卡（MapleLife）的职业码（0-4）。非对应入口为 -1。</li>
 *   <li>{@code candidates} = 每性别外观候选集在 {@code Etc.wz/MakeCharInfo.img} 内的相对
 *       路径——创建外观校验的唯一数据源（原 ItemConstants 硬编码白名单已废弃）。</li>
 *   <li>{@code mapleLifeEnhance} = 老兵卡 SP 强化修正（null = 该职业无强化，如弓手/飞侠/海盗）；
 *       improveSp 为客户端提交的强化档位（0 = 不强化）。</li>
 *   <li>{@code baseData} = CharacterData 子集（stats/inventory/skills）；运行时域
 *       （debuffs/pets/antiCheat）不适用。inventory 中 position &lt; 0 的物品为穿戴位。</li>
 * </ul>
 */
public record CharacterTemplate(
        String id,
        int jobId,
        int level,
        int mapId,
        int ap,
        int sp,
        int meso,
        boolean novice,
        int noviceJobCode,
        int mapleLifeJobCode,
        CandidateSets candidates,
        MapleLifeEnhance mapleLifeEnhance,
        BaseData baseData
) {

    /** 每性别外观候选集的 wz 相对路径（Etc.wz/MakeCharInfo.img 内） */
    public record CandidateSets(String male, String female) {
    }

    /**
     * 老兵卡 SP 强化：improveSp 档位 &gt; 0 时扣 {@code spCost} 点 SP，先给 {@code enhanceSkill}
     * {@code spCost} 点、余量给 {@code overflowSkill}；maxHp += hpGain[improveSp]（战士）/
     * maxMp += mpGain[improveSp]（魔法师）。
     */
    public record MapleLifeEnhance(int[] hpGain, int[] mpGain, int spCost, int enhanceSkill, int overflowSkill) {
    }

    /** 基础角色数据（CharacterData 子集） */
    public record BaseData(CharacterStatsData stats, Map<String, List<ItemData>> inventory, Map<Integer, Integer> skills) {
    }

    /** 按性别取候选集路径 */
    public String candidatesFor(boolean female) {
        return female ? candidates.female() : candidates.male();
    }
}
