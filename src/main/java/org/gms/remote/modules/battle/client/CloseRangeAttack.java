package org.gms.remote.modules.battle.client;

import java.util.List;

/**
 * 近战攻击语义载荷（battle 模块词汇表，int 承载）：C→S 攻击包的原始 wire 字段——
 * 伤害值为客户端原始申报（未改写），超上限暴击反转 / 固定伤害重掷等语义改写归
 * gameplay Handler（no-peek：技能等级、伤害上限公式、怪物属性克制都是角色/地图知识）。
 *
 * @param numAttacked 目标怪数（wire 复合字节高 nibble，decode 拆出）
 * @param numDamage   每怪伤害段数（wire 复合字节低 nibble，decode 拆出）
 * @param skill       技能 id（0 = 普攻）
 * @param charge      蓄力技能内嵌 dword（非蓄力技能恒 0）
 * @param display     display 字节（原样透传，中继重放必需）
 * @param direction   攻击朝向字节（原样透传）
 * @param stance      stance 字节（原样透传，中继重放必需）
 * @param speed       攻击速度（解码缺省 4，wire 有值则覆盖）
 * @param targets     目标怪序列（wire 序；伤害段为客户端原始申报值）
 * @param mesoOids    MESO_EXPLOSION 引爆的目标物 oid（无伤害段）
 * @param positionX   POISON_BOMB 落点 x（其余技能恒 0）
 * @param positionY   POISON_BOMB 落点 y（其余技能恒 0）
 */
public record CloseRangeAttack(int numAttacked, int numDamage, int skill, int charge, int display, int direction,
                               int stance, int speed, List<Target> targets, List<Integer> mesoOids,
                               int positionX, int positionY) {

    public CloseRangeAttack {
        targets = List.copyOf(targets);
        mesoOids = List.copyOf(mesoOids);
    }

    /** 单个目标怪：oid + 伤害段序列（wire 序；值未改写） */
    public record Target(int oid, List<Integer> damages) {

        public Target {
            damages = damages == null ? null : List.copyOf(damages);
        }
    }
}
