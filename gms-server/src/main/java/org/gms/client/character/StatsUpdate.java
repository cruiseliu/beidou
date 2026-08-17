package org.gms.client.character;

/**
 * 属性更新参数对象：一次原子变更要写入的各属性目标值（绝对值），null = 不变更。
 * 通过 AbstractCharacterObject.applyUpdate / applyUpdateSilently 应用，
 * 替代原先 calcStatPoolLong 把四个值拼进 long 的隐式打包。
 * 字段 package-private 仅供同包读取，写入走流式 setter。
 */
public class StatsUpdate {
    Integer hp, mp, maxHp, maxMp;
    Integer str, dex, int_, luk;
    Integer ap;
    Integer sp;        // AVAILABLESP 的目标值
    Integer skillbook; // sp 所属技能书

    public StatsUpdate setHp(int hp) {
        this.hp = hp;
        return this;
    }

    public StatsUpdate setMp(int mp) {
        this.mp = mp;
        return this;
    }

    public StatsUpdate setMaxHp(int maxHp) {
        this.maxHp = maxHp;
        return this;
    }

    public StatsUpdate setMaxMp(int maxMp) {
        this.maxMp = maxMp;
        return this;
    }

    public StatsUpdate setStr(int str) {
        this.str = str;
        return this;
    }

    public StatsUpdate setDex(int dex) {
        this.dex = dex;
        return this;
    }

    public StatsUpdate setInt(int int_) {
        this.int_ = int_;
        return this;
    }

    public StatsUpdate setLuk(int luk) {
        this.luk = luk;
        return this;
    }

    public StatsUpdate setAp(int ap) {
        this.ap = ap;
        return this;
    }

    /** 设置指定技能书的剩余 SP */
    public StatsUpdate setSp(int skillbook, int sp) {
        this.skillbook = skillbook;
        this.sp = sp;
        return this;
    }
}
