package org.gms.client.character;

/**
 * 倍率贡献桶（桶内取 max、桶间相乘；见 doc/10 分桶模型）。
 * 不同桶用于"需要相互叠加"的加成来源分组；同桶内互相竞争（取最大）。
 *
 * <ul>
 *   <li>{@link #ITEM}：背包/商城道具声明（倍率券等，经 enter/leave 钩子驱动）；</li>
 *   <li>{@link #EQUIP}：穿戴装备声明（精灵吊坠等，经 equip/unequip 钩子驱动）——
 *       与 ITEM 桶相乘叠加，是"道具操作别的桶"的首个实例。</li>
 * </ul>
 */
public enum RateBucket {
    ITEM,
    EQUIP
}
