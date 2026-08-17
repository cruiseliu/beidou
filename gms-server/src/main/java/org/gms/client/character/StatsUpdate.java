package org.gms.client.character;

import static org.gms.client.character.BaseStat.BASE_STAT_COUNT;

/**
 * 属性更新参数对象：一次原子变更要写入的各属性目标值（绝对值），null = 不变更。
 * 通过 Character.applyUpdate / applyUpdateSilently 应用，
 * 替代原先 calcStatPoolLong 把四个值拼进 long 的隐式打包。
 * 四维走 base 数组（下标见 BaseStat），SP 不在此管理。
 * 字段 package-private 仅供同包读取，写入走流式 setter。
 */
public class StatsUpdate {
    Integer hp, mp, maxHp, maxMp;
    final Integer[] attrs = new Integer[BASE_STAT_COUNT];
    Integer ap;

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

    public StatsUpdate setAttr(int idx, int value) {
        this.attrs[idx] = value;
        return this;
    }

    public StatsUpdate setAp(int ap) {
        this.ap = ap;
        return this;
    }
}
