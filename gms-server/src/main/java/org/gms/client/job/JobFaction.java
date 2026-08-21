package org.gms.client.job;

/**
 * 阵营（显式游戏机制）：常规阵营为冒险家 / 骑士团 / 英雄；GM 可并入。
 * 除新手外所有合法转职均不修改阵营；游戏内容明确指定阵营白名单。
 * 新阵营 = 在此加枚举值（零 id 推断）。
 */
public enum JobFaction {
    ADVENTURER,
    CYGNUS,
    HERO,
    GM
}
