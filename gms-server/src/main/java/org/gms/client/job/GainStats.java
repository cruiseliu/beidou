package org.gms.client.job;

/**
 * 一次授予的成长统计（升级每级 / 转职一次性），HP/MP 各自区间 + 单固定值乘数，AP/SP 为固定点数。
 * <p>
 * maxHp / maxMp：加法有下界/上界两个值（结果浮动）；multiply 只有一个单固定值（不浮动，
 * 1.0 = 无此机制，1.1 = +10%）。addMin = addMax 表示固定值（GM 等特例职业）。
 * <p>
 * 升级和转职共用本结构（对称）：升级是每级一份（挂在 LevelUpRange 区间上），转职是一次性一份。
 */
public record GainStats(Growth maxHp, Growth maxMp, int ap, int sp) {

    /** 单一维度的成长：加法区间 [addMin, addMax] + 单固定值乘数 multiply */
    public record Growth(int addMin, int addMax, double multiply) {
    }

    public static final GainStats NONE = new GainStats(new Growth(0, 0, 1.0), new Growth(0, 0, 1.0), 0, 0);

    public static GainStats of(int hpMin, int hpMax, int mpMin, int mpMax, int ap, int sp) {
        return new GainStats(new Growth(hpMin, hpMax, 1.0), new Growth(mpMin, mpMax, 1.0), ap, sp);
    }
}
