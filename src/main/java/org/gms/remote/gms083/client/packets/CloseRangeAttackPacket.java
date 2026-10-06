package org.gms.remote.gms083.client.packets;

import org.gms.constants.skills.Bishop;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.ChiefBandit;
import org.gms.constants.skills.Evan;
import org.gms.constants.skills.FPArchMage;
import org.gms.constants.skills.Gunslinger;
import org.gms.constants.skills.ILArchMage;
import org.gms.constants.skills.NightWalker;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.remote.modules.battle.client.CloseRangeAttack;

import java.util.ArrayList;
import java.util.List;

/**
 * 近战攻击包 codec（v83，C→S）：CLOSE_RANGE_ATTACK 的 decode（bytes → 语义载荷，纯函数）。
 * 布局与历史 AbstractDealDamageHandler.parseDamage 的近战分支逐字节对齐（含 MESO_EXPLOSION
 * 独立 body 与 POISON_BOMB 落点段）；只做纯解码——伤害值原样带出，不查角色/地图状态。
 *
 * <p>wire 布局条件（哪些技能内嵌蓄力 dword / 走引爆 body）以技能 id 为键——布局是版本
 * wire 知识，键值是游戏数据常量（只读常量依赖，不含行为）。
 */
public final class CloseRangeAttackPacket {

    private CloseRangeAttackPacket() {
    }

    /** 内嵌蓄力 dword 的技能（历史 parseDamage 近战分支同款判定集合） */
    private static boolean hasChargeDword(int skill) {
        return skill == Evan.ICE_BREATH || skill == Evan.FIRE_BREATH
                || skill == FPArchMage.BIG_BANG || skill == ILArchMage.BIG_BANG || skill == Bishop.BIG_BANG
                || skill == Gunslinger.GRENADE || skill == Brawler.CORKSCREW_BLOW
                || skill == ThunderBreaker.CORKSCREW_BLOW || skill == NightWalker.POISON_BOMB;
    }

    /** 解码近战攻击语义载荷（纯解码，无任何对象写入）。包头未用字节由本方法跳过（包布局归 codec）。 */
    public static CloseRangeAttack decode(ByteBufReader p) {
        p.skip(1);   // 包头未用字节（历史 parseDamage 首个 readByte）
        int numAttackedAndDamage = p.readByte();   // byte→int 符号扩展，与历史一致（nibble 提取不受影响）
        int numAttacked = (numAttackedAndDamage >>> 4) & 0xF;
        int numDamage = numAttackedAndDamage & 0xF;
        int skill = p.readInt();
        int charge = hasChargeDword(skill) ? p.readInt() : 0;
        p.skip(8);
        int display = p.readByte();
        int direction = p.readByte();
        int stance = p.readByte();

        List<CloseRangeAttack.Target> targets = new ArrayList<>();
        List<Integer> mesoOids = new ArrayList<>();

        if (skill == ChiefBandit.MESO_EXPLOSION) {
            // 引爆独立 body：无怪段 = 纯 meso oid 表；有怪段 = 怪（无每怪 skip 12）+ 末组 meso oid 表
            if (numAttackedAndDamage == 0) {
                p.skip(10);
                int bullets = p.readByte();
                for (int j = 0; j < bullets; j++) {
                    mesoOids.add(p.readInt());
                    p.skip(1);
                }
                return new CloseRangeAttack(numAttacked, numDamage, skill, charge, display, direction, stance,
                        4, targets, mesoOids, 0, 0);
            }
            p.skip(6);
            for (int i = 0; i < numAttacked + 1; i++) {
                int oid = p.readInt();
                if (i < numAttacked) {
                    p.skip(12);
                    int bullets = p.readByte();
                    List<Integer> damages = new ArrayList<>(bullets);
                    for (int j = 0; j < bullets; j++) {
                        damages.add(p.readInt());
                    }
                    targets.add(new CloseRangeAttack.Target(oid, damages));
                    p.skip(4);
                } else {
                    int bullets = p.readByte();
                    for (int j = 0; j < bullets; j++) {
                        mesoOids.add(p.readInt());
                        p.skip(1);
                    }
                }
            }
            return new CloseRangeAttack(numAttacked, numDamage, skill, charge, display, direction, stance,
                    4, targets, mesoOids, 0, 0);
        }

        p.readByte();   // 未用字节（历史近战段首个 readByte）
        int speed = p.readByte();
        p.skip(4);

        for (int i = 0; i < numAttacked; i++) {
            int oid = p.readInt();
            p.skip(14);
            List<Integer> damages = new ArrayList<>(numDamage);
            for (int j = 0; j < numDamage; j++) {
                damages.add(p.readInt());
            }
            // 每怪尾 skip(4)：历史条件表达式（skill != A || skill != B ...）恒真，恒跳过
            p.skip(4);
            targets.add(new CloseRangeAttack.Target(oid, damages));
        }

        int positionX = 0;
        int positionY = 0;
        if (skill == NightWalker.POISON_BOMB) {
            p.skip(4);
            positionX = p.readShort();
            positionY = p.readShort();
        }
        return new CloseRangeAttack(numAttacked, numDamage, skill, charge, display, direction, stance,
                speed, targets, mesoOids, positionX, positionY);
    }
}
