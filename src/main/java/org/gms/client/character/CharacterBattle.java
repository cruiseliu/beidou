package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.MapId;
import org.gms.constants.skills.*;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.modules.battle.BattleModule;
import org.gms.remote.modules.battle.client.CloseRangeAttack;
import org.gms.server.BuffEffectData;
import org.gms.server.maps.Battle;
import org.gms.util.AssertUtil;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战斗模块组件：CLOSE_RANGE_ATTACK 近战攻击 phase 1（player actor，目标无关）。
 *
 * <p>机械复制自 legacy CloseRangeDamageHandler + AbstractDealDamageHandler 的近战路径，
 * 语义逐行等价；未用的特判分支一律断言（最简版：普攻 skill==0），特判技能后续换更通用
 * 的方式实现。phase 1 只做目标无关处理（attacker 伤害模型/状态守卫/申报校验），产出
 * {@link Battle.CloseRangeAttackIntent} post map actor——目标相关处理、伤害最终化
 * （暴击反转）与中继广播在 phase 2（org.gms.server.maps.Battle，map actor 域内）。
 */
class CharacterBattle implements BattleModule.Handler {

    private final Character owner;

    CharacterBattle(Character owner) {
        this.owner = owner;
        // 不在构造期自注册：构造上下文无 actor 可达（autosave/charlist 装载，doc/12）
    }

    /** 收包 Handler 接插（角色入场绑定时由 Character 聚合调用，on strand，doc/12） */
    void bindClientHandlers(ClientEventHandlerRegistry registry) {
        registry.registerBattle(this);
    }

    @Override
    public void closeRangeAttack(CloseRangeAttack data) {
        Character chr = owner;

        AttackInfo attack = parseCloseRange(data, chr);

        BuffEffectData morph = chr.getBuffEffect(EffectType.MORPH);
        AssertUtil.isTrue(morph == null || !morph.isMorphWithoutAttack());

        AssertUtil.isTrue(!MapId.isNettsPyramid(chr.getMapId()));
        AssertUtil.isTrue(!MapId.isDojo(chr.getMapId()));

        AssertUtil.isTrue(!GameConstants.isFinisherSkill(attack.skill));
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.COMBO) == null);

        AssertUtil.isTrue(chr.getSkillLevel(15100004) == 0);
        AssertUtil.isTrue(chr.getSkillLevel(5110001) == 0);

        AssertUtil.isTrue(attack.skill != DragonKnight.SACRIFICE);

        AssertUtil.isTrue(attack.skill != 1211002);

        AssertUtil.isTrue(attack.skill == 0);
        /*
        if (attack.skill != 0) {
            attackCount = attack.getAttackEffect(chr, null).getAttackCount();
        }

        if (attack.skill > 0) {
            Skill skill = SkillFactory.getSkill(attack.skill);
            int skillLevel = chr.getSkillLevel(attack.skill);
            BuffEffectData effect_ = skill.getEffect(skillLevel);
            if (effect_.getCooldown() > 0) {
                if (chr.skillIsCooling(attack.skill)) {
                    return;
                } else {
                    c.sendPacket(PacketCreator.skillCooldown(attack.skill, effect_.getCooldown()));
                    chr.addCooldown(attack.skill, Server.getInstance().getCurrentTime(), SECONDS.toMillis(effect_.getCooldown()));
                }
            }
        }
        */

        AssertUtil.isTrue(chr.getSkillLevel(NightWalker.VANISH) == 0);
        AssertUtil.isTrue(chr.getSkillLevel(Rogue.DARK_SIGHT) == 0);
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.DARKSIGHT) == null);

        AssertUtil.isTrue(chr.getSkillLevel(WindArcher.WIND_WALK) == 0);
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.WIND_WALK) == null);

        // —— on-hit 特效前置守卫（原 applyAttack 的 player 态断言，目标无关故上提；
        // 全量版对应块展开为「player actor 内结算 → 数据并入意图」）——
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.PICKPOCKET) == null);
        AssertUtil.isTrue(attack.skill != Marauder.ENERGY_DRAIN);
        AssertUtil.isTrue(attack.skill != ThunderBreaker.ENERGY_DRAIN);
        AssertUtil.isTrue(attack.skill != NightWalker.VAMPIRE);
        AssertUtil.isTrue(attack.skill != Assassin.DRAIN);
        AssertUtil.isTrue(attack.skill != Bandit.STEAL);
        AssertUtil.isTrue(attack.skill != FPArchMage.FIRE_DEMON);
        AssertUtil.isTrue(attack.skill != ILArchMage.ICE_DEMON);
        AssertUtil.isTrue(attack.skill != Outlaw.HOMING_BEACON);
        AssertUtil.isTrue(attack.skill != Corsair.BULLSEYE);
        AssertUtil.isTrue(attack.skill != Outlaw.FLAME_THROWER);
        AssertUtil.isTrue(!chr.isAran());
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.HAMSTRING) == null);
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.SLOW) == null);
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.BLIND) == null);
        AssertUtil.isTrue(chr.getJob().getId() != 121);
        AssertUtil.isTrue(chr.getJob().getId() != 122);
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.COMBO_DRAIN) == null);
        AssertUtil.isTrue(chr.getJob().getId() != 412);
        AssertUtil.isTrue(chr.getJob().getId() != 422);
        AssertUtil.isTrue(chr.getJob().getId() != 1411);
        AssertUtil.isTrue(chr.getJob().getId() < 311 || chr.getJob().getId() > 322);

        if (!chr.isAlive()) {
            return;
        }

        // phase 1 收口：意图 post map actor（phase 2 目标相关处理 + 伤害最终化 + 中继，
        // 见 org.gms.server.maps.Battle）。declaredDamage 按 allDamage.keySet() 现序冻结
        // ——即历史 relay 的 keySet 迭代序（HashMap 桶序），多目标字节一致性必需。
        final Map<Integer, List<Integer>> declaredOrder = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : attack.allDamage.entrySet()) {
            declaredOrder.put(entry.getKey(), entry.getValue());
        }
        owner.getMapRef().applyCloseRangeAttack(new Battle.CloseRangeAttackIntent(
                chr.ref(),
                chr.getId(),
                attack.skill,
                attack.skilllevel,
                attack.stance,
                attack.numAttackedAndDamage,
                attack.speed,
                attack.direction,
                attack.display,
                attack.dmgCap,
                attack.canCrit,
                declaredOrder
        ));
    }

    /**
     * 语义载荷 → AttackInfo（历史 parseDamage 近战路径的 role 拆分）：字段回填 +
     * attacker 伤害上限模型（目标无关段）。逐 hit 的暴击反转与目标相关调整不再在此——
     * 上限的最终值归 phase 2（map actor）才算齐。
     */
    private static AttackInfo parseCloseRange(CloseRangeAttack data, Character chr) {
        AttackInfo ret = new AttackInfo();
        ret.numAttacked = data.numAttacked();
        ret.numDamage = data.numDamage();
        // 复合字节回填（中继重放需要）：低 8 位由两个 nibble 完全决定，writeByte 取低 8 位
        // ——与历史 wire 值逐字节一致（符号扩展在 0xF 掩码下无差）。
        ret.numAttackedAndDamage = (ret.numAttacked << 4) | ret.numDamage;
        ret.skill = data.skill();
        ret.charge = data.charge();
        ret.display = data.display();
        ret.direction = data.direction();
        ret.stance = data.stance();
        ret.speed = data.speed();
        ret.position.setLocation(data.positionX(), data.positionY());

        AssertUtil.isTrue(ret.skill == 0);
        /*
        if (ret.skill > 0) {
            ret.skilllevel = chr.getSkillLevel(ret.skill);
            if (ret.skilllevel == 0 && GameConstants.isPqSkillMap(chr.getMapId()) && GameConstants.isPqSkill(ret.skill)) {
                ret.skilllevel = 1;
            }
        }
        */

        AssertUtil.isTrue(ret.skill != ChiefBandit.MESO_EXPLOSION);

        // Find the base damage to base further calculations on.
        // Several skills have their own formula in this section.
        long calcDmgMax;

        AssertUtil.isTrue(ret.skill != Rogue.LUCKY_SEVEN);
        AssertUtil.isTrue(ret.skill != NightWalker.LUCKY_SEVEN);
        AssertUtil.isTrue(ret.skill != NightLord.TRIPLE_THROW);

        AssertUtil.isTrue(ret.skill != DragonKnight.DRAGON_ROAR);

        AssertUtil.isTrue(ret.skill != NightLord.VENOMOUS_STAR);
        AssertUtil.isTrue(ret.skill != Shadower.VENOMOUS_STAB);


        calcDmgMax = chr.calculateMaxBaseDamage(chr.getTotalWatk());

        BuffEffectData effect = null;
        AssertUtil.isTrue(ret.skill == 0);
        /*
        if (ret.skill != 0) {
            Skill skill = SkillFactory.getSkill(ret.skill);
            effect = skill.getEffect(ret.skilllevel);

            if (ret.skill == Hermit.SHADOW_MESO) {
                // Shadow Meso also has its own formula
                calcDmgMax = effect.getMoneyCon() * 10;
                calcDmgMax = (int) Math.floor(calcDmgMax * 1.5);
            } else {
                // Normal damage formula for skills
                calcDmgMax = calcDmgMax * effect.getDamage() / 100;
            }
        }
        */

        AssertUtil.isTrue(chr.getBuffedValue(EffectType.COMBO) == null);

        AssertUtil.isTrue(chr.getEnergyBar() == 0);

        int bonusDmgBuff = 100;
        for (BuffEffectData buffEffect : chr.getAllBuffs()) {
            int bonusDmg = buffEffect.getDamage() - 100;
            bonusDmgBuff += bonusDmg;
        }

        if (bonusDmgBuff != 100) {
            float dmgBuff = bonusDmgBuff / 100.0f;
            calcDmgMax = (long) Math.ceil(calcDmgMax * dmgBuff);
        }

        AssertUtil.isTrue(chr.getMapId() < MapId.ARAN_TUTORIAL_START || chr.getMapId() > MapId.ARAN_TUTORIAL_MAX);

        AssertUtil.isTrue(!chr.getJob().isA((JobEnum.BOWMAN)));
        AssertUtil.isTrue(!chr.getJob().isA(JobEnum.THIEF));
        AssertUtil.isTrue(!chr.getJob().isA(JobEnum.NIGHTWALKER1));
        AssertUtil.isTrue(!chr.getJob().isA(JobEnum.WINDARCHER1));
        AssertUtil.isTrue(!chr.getJob().isA(JobEnum.ARAN3));
        AssertUtil.isTrue(!chr.getJob().isA(JobEnum.ARAN4));
        AssertUtil.isTrue(!chr.getJob().isA(JobEnum.MARAUDER));
        AssertUtil.isTrue(!chr.getJob().isA(JobEnum.BUCCANEER));
        boolean canCrit = false;
        ret.dmgCap = calcDmgMax;
        ret.canCrit = canCrit;

        AssertUtil.isTrue(chr.getBuffEffect(EffectType.SHARP_EYES) == null);

        AssertUtil.isTrue(chr.getBuffEffect(EffectType.SHADOWPARTNER) == null);

        AssertUtil.isTrue(ret.skill == 0);
        /*
        if (ret.skill != 0) {
            int fixed = ret.getAttackEffect(chr, SkillFactory.getSkill(ret.skill)).getFixDamage();
            if (fixed > 0) {
                calcDmgMax = fixed;
            }
        }
        */

        for (CloseRangeAttack.Target target : data.targets()) {
            int oid = target.oid();
            List<Integer> allDamageNumbers = new ArrayList<>();
            // Monster monster = chr.getMap().getMonsterByOid(oid);

            AssertUtil.isTrue(chr.getBuffEffect(EffectType.WK_CHARGE) == null);

            AssertUtil.isTrue(ret.skill == 0);
            /*
            if (ret.skill != 0) {
                Skill skill = SkillFactory.getSkill(ret.skill);
                if (skill.getElement() != Element.NEUTRAL && chr.getBuffedValue(EffectType.ELEMENTAL_RESET) == null) {
                    // The skill has an element effect, so we need to factor that in.
                    if (monster != null) {
                        ElementalEffectiveness eff = monster.getElementalEffectiveness(skill.getElement());
                        if (eff == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.5;
                        } else if (eff == ElementalEffectiveness.STRONG) {
                            //calcDmgMax *= 0.5;
                        }
                    } else {
                        // Since we already know the skill has an elemental attribute, but we dont know if the monster is weak or not, lets
                        // take the safe approach and just assume they are weak.
                        calcDmgMax *= 1.5;
                    }
                }
                if (ret.skill == FPWizard.POISON_BREATH || ret.skill == FPMage.POISON_MIST || ret.skill == FPArchMage.FIRE_DEMON || ret.skill == ILArchMage.ICE_DEMON) {
                    if (monster != null) {
                        // Turns out poison is completely server side, so I don't know why I added this. >.<
                        //calcDmgMax = monster.getHp() / (70 - chr.getSkillLevel(skill));
                    }
                } else if (ret.skill == Hermit.SHADOW_WEB) {
                    if (monster != null) {
                        calcDmgMax = monster.getHp() / (50 - chr.getSkillLevel(ret.skill));
                    }
                } else if (ret.skill == Hermit.SHADOW_MESO) {
                    if (monster != null) {
                        monster.debuffMob(Hermit.SHADOW_MESO);
                    }
                } else if (ret.skill == Aran.BODY_PRESSURE) {
                    if (monster != null) {
                        int bodyPressureDmg = (int) Math.ceil(monster.getMaxHp() * SkillFactory.getSkill(Aran.BODY_PRESSURE).getEffect(ret.skilllevel).getDamage() / 100.0);
                        if (bodyPressureDmg > calcDmgMax) {
                            calcDmgMax = bodyPressureDmg;
                        }
                    }
                }
            }
            */

            for (int j = 0; j < ret.numDamage; j++) {
                int damage = target.damages().get(j);
                long hitDmgMax = calcDmgMax;

                AssertUtil.isTrue(ret.skill != Buccaneer.BARRAGE);
                AssertUtil.isTrue(ret.skill != ThunderBreaker.BARRAGE);

                AssertUtil.isTrue(ret.skill != Marksman.SNIPE);

                AssertUtil.isTrue(ret.skill != Beginner.BAMBOO_RAIN);
                AssertUtil.isTrue(ret.skill != Noblesse.BAMBOO_RAIN);
                AssertUtil.isTrue(ret.skill != Evan.BAMBOO_THRUST);
                AssertUtil.isTrue(ret.skill != Evan.BAMBOO_THRUST);

                AssertUtil.isTrue(ret.skill != Marksman.SNIPE);
                AssertUtil.isTrue(!canCrit || damage <= hitDmgMax);
                /*
                if (canCrit && damage > hitDmgMax) {
                    // If the skill is a crit, inverse the damage to make it show up on clients.
                    damage = -Integer.MAX_VALUE + damage - 1;
                }
                */

                AssertUtil.isTrue(effect == null);
                /*
                if (effect != null) {
                    int maxattack = Math.max(effect.getBulletCount(), effect.getAttackCount());
                }
                */

                allDamageNumbers.add(damage);
            }
            ret.allDamage.put(oid, allDamageNumbers);
        }
        return ret;
    }

    public static class AttackInfo {

        public int numAttacked, numDamage, numAttackedAndDamage, skill, skilllevel, stance, direction, rangedirection, charge, display;
        public Map<Integer, List<Integer>> allDamage = new HashMap<>();
        public boolean ranged, magic;
        public int speed = 4;
        public Point position = new Point();
        public long dmgCap;
        public boolean canCrit;

        /*
        public BuffEffectData getAttackEffect(Character chr, Skill theSkill) {
            Skill mySkill = theSkill;
            if (mySkill == null) {
                mySkill = SkillFactory.getSkill(skill);
            }

            int skillLevel = chr.getSkillLevel(mySkill.getId());
            if (skillLevel == 0 && GameConstants.isPqSkillMap(chr.getMapId()) && GameConstants.isPqSkill(mySkill.getId())) {
                skillLevel = 1;
            }

            if (skillLevel == 0) {
                return null;
            }
            if (display > 80) { //Hmm
                if (!mySkill.getAction()) {
                    return null;
                }
            }
            return mySkill.getEffect(skillLevel);
        }
        */
    }
}
