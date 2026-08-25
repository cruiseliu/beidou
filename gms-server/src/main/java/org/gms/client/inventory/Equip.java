/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.client.inventory;

import org.gms.client.Client;
import org.gms.client.character.Stat;
import org.gms.config.GameConfig;
import org.gms.constants.game.ExpTable;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.gms.util.Randomizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

/**
 * 装备域信息（组件模式，对齐 CashItemInfo）：Item 持有本对象（构造时按背包类型定性），
 * 本对象持 owner 反向引用——物品公共状态（id/位置/数量/旗标/到期/现金域）一律走 owner，
 * 此处只保留装备语义（属性数组/升级槽/物品等级经验/戒指 id/可升级标记）。
 * 对外保留原 Item 侧同名委托方法（getFlag/getItemId/...）——position 已除名：
 * 调用方一律经宿主 Item（或 asItem()）读写字段位。
 */
public class Equip {
    private static final Logger log = LoggerFactory.getLogger(Equip.class);

    public enum ScrollResult {

        FAIL(0), SUCCESS(1), CURSE(2);
        private int value = -1;

        ScrollResult(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /** 属性数组（下标 = Stat.ordinal()，含装备段；仿 CharacterStats 的数组布局设计） */
    private int[] stats = new int[Stat.count()];

    private int enhancementLevel;
    private int enhancementSlots;
    private int vicious;

    // timeless or reverse, or any equip that could levelup on GMS for all effects
    /** wz 登记了成长升级表（info/level）：timeless/reverse/元素杖等 GMS 可成长装备。
     *  决定升级属性来源（wz 表 vs 通用随机）、经验吸收系数（0.85/0.6）与成长上限（wz 表最大级）。 */
    private boolean officialCanLevelUp = false;
    private int itemLevel;
    private int itemExp;

    private int ringid = -1;

    private static ItemInformationProvider ii = ItemInformationProvider.getInstance();

    private final Item owner;

    /** 包内构造：经 Item 构造器定性创建（外部创建装备物品走 Equip.create 工厂） */
    Equip(Item owner, int itemId) {
        this.owner = owner;
        this.itemExp = 0;
        this.itemLevel = 1;

        this.officialCanLevelUp = (ii.getEquipLevel(itemId, false) > 1);
    }

    /** 所属物品（公共状态的宿主） */
    public Item getItem() {
        return owner;
    }

    // ── 物品公共状态委托（owner 为宿主；保留原签名供内外部调用）──

    public int getItemId() {
        return owner.getItemId();
    }

    public String getOwner() {
        return owner.getOwner();
    }

    public void setOwner(String ownerName) {
        owner.setOwner(ownerName);
    }

    public long getExpiration() {
        return owner.getExpiration();
    }

    public void setExpiration(long expire) {
        owner.setExpiration(expire);
    }

    public CashItemInfo getCashInfo() {
        return owner.getCashInfo();
    }

    public boolean isCashItem() {
        return owner.isCashItem();
    }

    /** 属性数组访问（下标 = Stat.ordinal()；唯一属性读写入口） */
    public int getStat(Stat stat) {
        return stats[stat.ordinal()];
    }

    public void setStat(Stat stat, int value) {
        stats[stat.ordinal()] = value;
    }

    /** 装备信息深拷贝（owner 由 Item.copy 接线到新物品；公共字段拷贝在 Item.copy） */
    Equip copy(Item newOwner) {
        Equip ret = new Equip(newOwner, newOwner.getItemId());
        ret.enhancementSlots = enhancementSlots;
        ret.stats = stats.clone();
        ret.vicious = vicious;
        ret.itemLevel = itemLevel;
        ret.itemExp = itemExp;
        ret.enhancementLevel = enhancementLevel;
        ret.officialCanLevelUp = officialCanLevelUp;
        return ret;
    }

    public int getFlag() {
        return owner.getFlag();
    }

    public int getEnhancementSlots() {
        return enhancementSlots;
    }

    public int getVicious() {
        return vicious;
    }

    public void setFlag(int flag) {
        owner.setFlag(flag);
    }

    public void setVicious(int vicious) {
        this.vicious = vicious;
    }

    public void setEnhancementSlots(int upgradeSlots) {
        this.enhancementSlots = upgradeSlots;
    }

    public int getEnhancementLevel() {
        return enhancementLevel;
    }

    public void setEnhancementLevel(int level) {
        this.enhancementLevel = level;
    }

    public int getItemLevel() {
        return itemLevel;
    }

    private static int getStatModifier(boolean isAttribute) {
        // each set of stat points grants a chance for a bonus stat point upgrade at equip level up.

        if (GameConfig.getServerBoolean("use_equipment_level_up_power")) {
            if (isAttribute) {
                return 2;
            } else {
                return 4;
            }
        } else {
            if (isAttribute) {
                return 4;
            } else {
                return 16;
            }
        }
    }

    private static int randomizeStatUpgrade(int top) {
        int limit = Math.min(top, GameConfig.getServerInt("max_equipment_level_up_stat_up"));

        int poolCount = (limit * (limit + 1) / 2) + limit;
        int rnd = Randomizer.rand(0, poolCount);

        int stat = 0;
        if (rnd >= limit) {
            rnd -= limit;
            stat = 1 + (int) Math.floor((-1 + Math.sqrt((8 * rnd) + 1)) / 2);    // optimized randomizeStatUpgrade author: David A.
        }

        return stat;
    }

    private static boolean isPhysicalWeapon(int itemid) {
        Equip eqp = ii.getEquipById(itemid).getEquipInfo();
        return eqp.getStat(Stat.P_ATK) >= eqp.getStat(Stat.M_ATK);
    }

    private boolean isNotWeaponAffinity(Stat name) {
        // Vcoc's idea - WATK/MATK expected gains lessens outside of weapon affinity (physical/magic)

        if (ItemConstants.isWeapon(this.getItemId())) {
            if (name.equals(Stat.P_ATK)) {
                return !isPhysicalWeapon(this.getItemId());
            } else if (name.equals(Stat.M_ATK)) {
                return isPhysicalWeapon(this.getItemId());
            }
        }

        return false;
    }

    private void getUnitStatUpgrade(List<Pair<Stat, Integer>> stats, Stat name, int curStat, boolean isAttribute) {
        int maxUpgrade = randomizeStatUpgrade((int) (1 + (curStat / (getStatModifier(isAttribute) * (isNotWeaponAffinity(name) ? 2.7 : 1)))));
        if (maxUpgrade == 0) {
            return;
        }

        stats.add(new Pair<>(name, maxUpgrade));
    }

    private void improveDefaultStats(List<Pair<Stat, Integer>> stats) {
        for (Stat type : Stat.values()) {
            getUnitStatUpgrade(stats, type, getStat(type), true);
        }
    }

    /**
     * 装备升级时计算增加的属性值，值>0才显示，避免显示负数或者0，避免玩家以为属性被扣除了
     * 优化提示消息，使其更易懂
     * @param stats 属性升级列表，包含属性类型和增加值
     * @return 提示消息
     */
    public String gainStats(List<Pair<Stat, Integer>> stats) {
        StringBuilder lvupStr = new StringBuilder(); // 使用 StringBuilder 提高字符串拼接效率
        int maxStat = GameConfig.getServerInt("max_equipment_stat"); // 获取属性最大值

        for (Pair<Stat, Integer> stat : stats) { // 遍历属性升级列表
            Stat type = stat.getLeft(); // 属性类型
            int value = stat.getRight(); // 属性增加值

            switch (type) {
                default: // 处理普通属性
                    int statUp = handleStatUpgrade(type, value, maxStat);
                    if (statUp > 0) {
                        lvupStr.append(getStatMessage(type, statUp)).append("; ");
                    }
                    break;
            }
        }

        return lvupStr.toString();
    }

    /**
     * 处理普通属性的升级逻辑
     * @param type 属性类型
     * @param value 属性增加值
     * @param maxStat 属性最大值
     * @return 实际增加的属性值
     */
    private int handleStatUpgrade(Stat type, int value, int maxStat) {
        int currentStat = getCurrentStat(type); // 获取当前属性值
        int statUp = Math.min(value, maxStat - currentStat); // 计算实际增加值，不超过最大值
        if (statUp > 0) {
            setCurrentStat(type, currentStat + statUp); // 更新属性值
        }
        return statUp;
    }

    /**
     * 获取当前属性值
     * @param type 属性类型
     * @return 当前属性值
     */
    private int getCurrentStat(Stat type) {
        return getStat(type);
    }

    /**
     * 设置当前属性值
     * @param type 属性类型
     * @param value 新的属性值
     */
    private void setCurrentStat(Stat type, int value) {
        setStat(type, value);
    }

    /**
     * 获取属性提升的提示消息
     * @param type 属性类型
     * @param value 属性增加值
     * @return 提示消息
     */
    private String getStatMessage(Stat type, int value) {
        String messageKey = "Equip.gainStats." + type.name().substring(3); // 从 incDEX 中提取 DEX
        return I18nUtil.getMessage(messageKey) + "+" + value;
    }

    /**
     * 处理装备升级的逻辑，包括属性提升、升级槽增加、金锤子减少等，并通知客户端更新装备状态
     * @param c 触发升级的客户端
     */
    /** 是否存在任一非零属性（通用升级 roll 的前置条件；白板装备升级不出属性） */
    private boolean hasAnyStat() {
        for (int v : stats) {
            if (v != 0) {
                return true;
            }
        }
        return false;
    }

    private void gainLevel(Client c) {
        List<Pair<Stat, Integer>> stats = new LinkedList<>(); // 初始化属性升级列表

        if (officialCanLevelUp) {// wz 成长表装备：升级属性从 wz 表读取
            List<Pair<String, Integer>> elementalStats = ii.getItemLevelupStats(getItemId(), itemLevel);
            for (Pair<String, Integer> p : elementalStats) {
                if (p.getRight() > 0) { // 只有增加值大于0时才添加到列表
                    stats.add(new Pair<>(Stat.valueOf(p.getLeft()), p.getRight()));
                }
            }
        }

        if (stats.isEmpty()) {// 属性列表为空：通用随机升级（wz 表缺失或该级全 miss）
            improveDefaultStats(stats); // 生成默认属性升级列表
            if (hasAnyStat()) {// 非白板装备保底至少出一条属性（白板无属性可 roll，白升一级）
                while (stats.isEmpty()) {
                    improveDefaultStats(stats);// 生成默认属性升级列表
                }
            }
        }

        itemLevel++; // 提升装备等级

        String lvupStr = I18nUtil.getMessage("Equip.gainStats.lvupStr", ii.getName(this.getItemId()), itemLevel) + "; ";  // 生成等级提升的提示消息

        lvupStr += this.gainStats(stats);    // 调用 gainStats 计算属性提升和生成提示消息

        // 通知客户端更新装备状态
        c.getPlayer().equipChanged();
        c.getPlayer().showHint(I18nUtil.getMessage("Equip.gainStats.showHint", ii.getName(this.getItemId()), itemLevel), 300); // 显示等级提升的消息
        c.getPlayer().dropMessage(6, lvupStr); // 显示属性提升的消息

        // 发送装备升级的效果包
        c.sendPacket(PacketCreator.showEquipmentLevelUp());
        c.getPlayer().getMap().broadcastPacket(c.getPlayer(), PacketCreator.showForeignEffect(c.getPlayer().getId(), 15));
        c.getPlayer().forceUpdateItem(owner.getSlot()); // 强制更新装备状态
    }

    public int getItemExp() {
        return itemExp;
    }

    private static double normalizedMasteryExp(int reqLevel) {
        // Conversion factor between mob exp and equip exp gain. Through many calculations, the expected for equipment levelup
        // from level 1 to 2 is killing about 100~200 mobs of the same level range, on a 1x EXP rate scenario.

        if (reqLevel < 5) {
            return 42;
        } else if (reqLevel >= 78) {
            return Math.max((10413.648 * Math.exp(reqLevel * 0.03275)), 15);
        } else if (reqLevel >= 38) {
            return Math.max((4985.818 * Math.exp(reqLevel * 0.02007)), 15);
        } else if (reqLevel >= 18) {
            return Math.max((248.219 * Math.exp(reqLevel * 0.11093)), 15);
        } else {
            return Math.max(((1334.564 * Math.log(reqLevel)) - 1731.976), 15);
        }
    }

    /**
     * 处理装备经验值的增加逻辑（Ronan 的装备经验值获取方法）
     * @param c 客户端对象
     * @param gain 获得的经验值
     */
    public synchronized void gainItemExp(Client c, int gain) {
        if (!ii.isUpgradeable(this.getItemId())) {// 检查装备是否可升级
            return;
        }

        int equipMaxLevel = Math.min(30, Math.max(ii.getEquipLevel(this.getItemId(), true), GameConfig.getServerInt("use_equipment_level_up")));// 计算装备的最大等级
        if (itemLevel >= equipMaxLevel) {
            return;
        }

        int reqLevel = ii.getEquipLevelReq(this.getItemId());// 获取装备的需求等级

        // 计算经验值修正因子
        float masteryModifier = (GameConfig.getServerFloat("equip_exp_rate") * ExpTable.getExpNeededForLevel(1)) / (float) normalizedMasteryExp(reqLevel);
        float elementModifier = (officialCanLevelUp) ? 0.85f : 0.6f;

        float baseExpGain = gain * elementModifier * masteryModifier;// 计算实际获得的经验值

        itemExp += baseExpGain;// 更新装备经验值
        int expNeeded = ExpTable.getEquipExpNeededForLevel(itemLevel);

        // 调试信息：显示经验值获取详情
        if (GameConfig.getServerBoolean("use_debug_show_eqp_exp")) {
            log.info("{} -> EXP Gain: {}, Mastery: {}, Base gain: {}, exp: {} / {}, Kills TNL: {}", ii.getName(getItemId()),
                    gain, masteryModifier, baseExpGain, itemExp, expNeeded, expNeeded / (baseExpGain / c.getPlayer().getExpRate()));
        }


        if (itemExp >= expNeeded) {// 判断是否需要升级
            while (itemExp >= expNeeded) {
                itemExp -= expNeeded;
                gainLevel(c); // 升级装备

                if (itemLevel >= equipMaxLevel || !GameConfig.getServerBoolean("use_equipment_level_up_continuous")) {// 如果达到最大等级或者不允许连续升级，重置经验值并退出循环
                    itemExp = 0;
                    break;
                }

                expNeeded = ExpTable.getEquipExpNeededForLevel(itemLevel);// 更新升级所需经验值
            }
        }

        c.getPlayer().forceUpdateItem(owner.getSlot());// 通知客户端更新装备状态
    }

    private boolean reachedMaxLevel() {
        if (officialCanLevelUp) {
            if (itemLevel < ItemInformationProvider.getInstance().getEquipLevel(getItemId(), true)) {
                return false;
            }
        }

        return itemLevel >= GameConfig.getServerInt("use_equipment_level_up");
    }

    public String showEquipFeatures(Client c) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (!ii.isUpgradeable(this.getItemId())) {
            return "";
        }

        String eqpName = ii.getName(getItemId());
        String eqpInfo = reachedMaxLevel() ? " #e#rMAX LEVEL#k#n" : (" EXP: #e#b" + itemExp + "#k#n / " + ExpTable.getEquipExpNeededForLevel(itemLevel));

        return "'" + eqpName + "' -> LV: #e#b" + itemLevel + "#k#n    " + eqpInfo + "\r\n";
    }

    public void setItemExp(int exp) {
        this.itemExp = exp;
    }

    public void setItemLevel(int level) {
        this.itemLevel = level;
    }

    public void setUpgradeSlots(int i) {
        this.enhancementSlots = i;
    }

    public int getRingId() {
        return ringid;
    }

    public void setRingId(int id) {
        this.ringid = id;
    }
}
