package org.gms.client.character;

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

    /**
     * 重算 localMaxHp / localMaxMp：基础 + 装备 + 技能加成。
     * 由 Character 的 recalcLocalStats 在持锁状态下调用。
     */
    void recalcLocalMaxHpMp(int buffMaxHp, int buffMaxMp) {
        localMaxHp = maxHp + equipmaxhp + buffMaxHp;
        localMaxMp = maxMp + equipmaxmp + buffMaxMp;
        enforceHpMpBounds();
    }

    /**
     * 重算 local 四维/攻击/魔法：基础 + 装备 + buff 加成。
     */
    void recalcLocalDerived(int buffStr, int buffDex, int buffInt, int buffLuk,
                             int buffMagic, int buffWatk) {
        localstr = str + equipstr + buffStr;
        localdex = dex + equipdex + buffDex;
        localint_ = int_ + equipint_ + buffInt;
        localluk = luk + equipluk + buffLuk;
        localmagic = localint_ + equipmagic + buffMagic;
        localwatk = equipwatk + buffWatk;
    }
}
