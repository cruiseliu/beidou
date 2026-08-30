package org.gms.client.character;

/**
 * 倍率贡献桶（桶内取 max、桶间相乘；见 doc/10 分桶模型）。
 * 当前仅 ITEM 桶（道具声明条目，经 {@link CharacterRates} sink 寻址）；
 * WORLD/PLAYER 等其余桶在 CharacterRates 桶化重构时再加入。
 */
public enum RateBucket {
    ITEM
}
