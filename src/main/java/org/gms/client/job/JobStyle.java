package org.gms.client.job;

/**
 * 风格（显式游戏机制）：战士 / 魔法师 / 弓箭手 / 飞侠 / 海盗 + 新手。
 * 固定 5+1 种。非新手所有合法转职均不修改风格。
 *
 * 注意：弩手（CROSSBOWMAN）不是风格——它是 AP 分配器历史实现的特例参数，
 * 职业函数化后由弩手职业自己的分配器函数表达，不进入风格枚举。
 */
public enum JobStyle {
    BEGINNER,
    WARRIOR,
    MAGICIAN,
    BOWMAN,
    THIEF,
    PIRATE
}
