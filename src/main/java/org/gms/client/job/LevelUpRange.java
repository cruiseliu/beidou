package org.gms.client.job;

/**
 * 升级奖励区间：等级落在 (from, to] 内时，每级获得一份 gainStats（稀疏，二分查找命中）。
 * <p>
 * from 是【开区间】（exclusive）、to 是【闭区间】（inclusive）——
 * 转职发生的那一级只能以旧职业的身份获得升级奖励（先升级后转职），
 * 所以新职业的区间 from = 本职业转职等级，从 from+1 级起生效，天然 exclusive。
 * <p>
 * 例：新手 {from=1, to=7, sp=1}（2~7 级生效）、骑士团 {from=10, to=17, ap=3}（11~17 级生效）。
 */
public record LevelUpRange(int from, int to, GainStats gainStats) {
}
