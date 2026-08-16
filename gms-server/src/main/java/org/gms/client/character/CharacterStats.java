package org.gms.client.character;

import org.gms.client.Job;
import org.gms.util.Pair;
import org.gms.util.Randomizer;

/**
 * 角属属性数据 + 纯计算。无 I/O、无发包、无锁（锁由 Character 持有并通过 StatLock 使用）。
 * 字段全部 package-private，同包的 Character 直接访问，不提供 getter/setter。
 */
public class CharacterStats {
    // ── 基础属性 ──
    int str, dex, int_, luk;

    // ── HP / MP ──
    int hp, mp;
    int maxHp, maxMp;
    int clientMaxHp, clientMaxMp;
    float transientHp = Float.NEGATIVE_INFINITY;
    float transientMp = Float.NEGATIVE_INFINITY;

    // ── local 系列（派生属性，recalc 后有效） ──
    int localstr, localdex, localluk, localint_;
    int localmagic, localwatk;
    int localMaxHp = 50, localMaxMp = 5;

    // ── equip 系列（装备聚合中间值，recalcEquipStats 的输出、local 计算的输入） ──
    int equipstr, equipdex, equipluk, equipint_;
    int equipmagic, equipwatk;
    int equipmaxhp, equipmaxmp;

    int localchairrate = -1;  // -1 = 需重算（reapplyLocalStats 中重置，updateChairHealStats 中检查）
    int localchairhp;
    int localchairmp;

    // ── 操作方法（package-private，由 Character 在持锁状态下调用） ──

    void setHp(int newHp) {
        this.hp = newHp;
        if (this.hp < 0) this.hp = 0;
        if (this.hp > localMaxHp) this.hp = localMaxHp;
        transientHp = Float.NEGATIVE_INFINITY;
    }

    void setMp(int newMp) {
        this.mp = newMp;
        if (this.mp < 0) this.mp = 0;
        if (this.mp > localMaxMp) this.mp = localMaxMp;
        transientMp = Float.NEGATIVE_INFINITY;
    }

    void setMaxHp(int newMaxHp) {
        this.maxHp = newMaxHp;
        clientMaxHp = Math.min(30000, newMaxHp);
    }

    void setMaxMp(int newMaxMp) {
        this.maxMp = newMaxMp;
        clientMaxMp = Math.min(30000, newMaxMp);
    }

    void setStr(int str) { this.str = str; }
    void setDex(int dex) { this.dex = dex; }
    void setInt(int int_) { this.int_ = int_; }
    void setLuk(int luk) { this.luk = luk; }

    void addMaxHp(int amount) {
        setMaxHp(maxHp + amount);
    }

    void addMaxMp(int amount) {
        setMaxMp(maxMp + amount);
    }

    void enforceHpMpBounds() {
        if (hp > localMaxHp) hp = localMaxHp;
        if (mp > localMaxMp) mp = localMaxMp;
    }

    boolean isAlive() {
        return hp > 0;
    }

    // ── 工具函数 ──

    static int getHpMpGainFromRange(int min, int max, boolean fixed) {
        return fixed ? (min + max) / 2 : Randomizer.rand(min, max);
    }

    static int calcTransientRatio(float transientpoint) {
        int ret = (int) transientpoint;
        return !(ret <= 0 && transientpoint > 0.0f) ? ret : 1;
    }

    // ── 升级/转职 基础 HP/MP 计算 ──

    Pair<Integer, Integer> getBasicLevelUpHpMp(Job job) {
        boolean fixedLevelUpHpMp = true;  // todo: [refactor] hard coded config
        int hp = 0, mp = 0;
        if (job.isBeginnerJob()) {
            hp = getHpMpGainFromRange(12, 16, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(10, 12, fixedLevelUpHpMp);
        } else if (job.isA(Job.WARRIOR) || job.isA(Job.DAWNWARRIOR1)) {
            hp = getHpMpGainFromRange(24, 28, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(4, 6, fixedLevelUpHpMp);
        } else if (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1)) {
            hp = getHpMpGainFromRange(10, 14, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(22, 24, fixedLevelUpHpMp);
        } else if (job.isA(Job.BOWMAN) || job.isA(Job.THIEF) || (job.getId() > 1299 && job.getId() < 1500)) {
            hp = getHpMpGainFromRange(20, 24, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(14, 16, fixedLevelUpHpMp);
        } else if (job.isA(Job.GM)) {
            hp = 30000;
            mp = 30000;
        } else if (job.isA(Job.PIRATE) || job.isA(Job.THUNDERBREAKER1)) {
            hp = getHpMpGainFromRange(22, 28, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(18, 23, fixedLevelUpHpMp);
        } else if (job.isA(Job.ARAN1)) {
            hp = getHpMpGainFromRange(44, 48, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(4, 8, fixedLevelUpHpMp);
            mp += (int) Math.floor(mp * 0.1);
        }
        return new Pair<>(hp, mp);
    }

    // ── HP/MP 比例计算（use_fixed_ratio_hpmp_update 启用时） ──

    int calcHpRatioUpdate(int curpoint, int maxpoint, int diffpoint) {
        int nextMax = Math.min(30000, maxpoint + diffpoint);
        float temp = curpoint * nextMax;
        int ret = (int) Math.ceil(temp / maxpoint);
        transientHp = (maxpoint > nextMax) ? ((float) curpoint) / maxpoint : ((float) ret) / nextMax;
        return ret;
    }

    int calcMpRatioUpdate(int curpoint, int maxpoint, int diffpoint) {
        int nextMax = Math.min(30000, maxpoint + diffpoint);
        float temp = curpoint * nextMax;
        int ret = (int) Math.ceil(temp / maxpoint);
        transientMp = (maxpoint > nextMax) ? ((float) curpoint) / maxpoint : ((float) ret) / nextMax;
        return ret;
    }

    int calcHpFromTransient() {
        return calcTransientRatio(transientHp * localMaxHp);
    }

    int calcMpFromTransient() {
        return calcTransientRatio(transientMp * localMaxMp);
    }

    // ── 装备属性聚合 ──

    /**
     * 将装备聚合值清零后累加指定装备列表的属性。
     * Character 负责传入 EQUIPPED 背包内容和管理 equipchanged 标志。
     */
    void aggregateEquipStats(Iterable<org.gms.client.inventory.Equip> equips) {
        equipmaxhp = 0;
        equipmaxmp = 0;
        equipdex = 0;
        equipint_ = 0;
        equipstr = 0;
        equipluk = 0;
        equipmagic = 0;
        equipwatk = 0;

        for (var equip : equips) {
            equipmaxhp += equip.getHp();
            equipmaxmp += equip.getMp();
            equipdex += equip.getDex();
            equipint_ += equip.getInt();
            equipstr += equip.getStr();
            equipluk += equip.getLuk();
            equipmagic += equip.getMatk() + equip.getInt();
            equipwatk += equip.getWatk();
        }
    }

    /** 把 equip* 聚合值加到 local* 上（在 local* 已重置为基础值后调用）。 */
    void applyEquipToLocal() {
        localMaxHp += equipmaxhp;
        localMaxMp += equipmaxmp;
        localdex += equipdex;
        localint_ += equipint_;
        localstr += equipstr;
        localluk += equipluk;
        localmagic += equipmagic;
        localwatk += equipwatk;
    }

    /** reapplyLocalStats 的第一步：把 local* 重置为基础属性值。 */
    void resetLocalToBase() {
        localMaxHp = maxHp;
        localMaxMp = maxMp;
        localdex = dex;
        localint_ = int_;
        localstr = str;
        localluk = luk;
        localmagic = localint_;
        localwatk = 0;
        localchairrate = -1;
    }
}

