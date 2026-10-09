package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.MapId;
import org.gms.constants.skills.*;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.modules.battle.BattleModule;
import org.gms.client.quest.medal.SpecialChallengeMedal;
import org.gms.client.quest.medal.VeteranHunterMedal;
import org.gms.remote.modules.battle.client.CloseRangeAttack;
import org.gms.server.BuffEffectData;
import org.gms.server.life.MonsterDropEntry;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.Battle;
import org.gms.server.maps.MapObjectType;
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
        final Battle.DropEntitlement dropEntitlement = buildDropEntitlement(chr, attack);
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
                declaredOrder,
                dropEntitlement
        ));

        // 死亡结算由 map actor 逐参与者回投（monsterKilled）

    }

    /**
     * 掉落权益快照构造（点1：倍率随 attack info post；全本体直读，自 strand 自访）。
     * 卡倍率按目标怪静态掉落表逐条目解析（只收 ≠1.0 命中项；meso 走 mesoDropRate 不入表）；
     * view miss 的目标无条目（缺省 1.0，登记在案——正常流 FIFO 保证 view 先于攻击落地）。
     * MIP 首访含 DB 读（每怪类型一次，retrieveDrop 已加锁）。
     */
    private static Battle.DropEntitlement buildDropEntitlement(Character chr, AttackInfo attack) {
        double dropRate = chr.getDropRate();
        if (chr.isFamilyBuff()) {
            dropRate *= chr.getFamilyDrop();
        }

        double mesoRate = chr.getMesoRate();
        Integer mesoUp = chr.getBuffedValue(EffectType.MESOUP);   // 4111001 Hermit.MESO_UP
        if (mesoUp != null) {
            mesoRate *= mesoUp.doubleValue() / 100.0;
        }

        List<Battle.DropEntitlement.PerItemDropRate> cardRates = new ArrayList<>();
        for (Integer oid : attack.allDamage.keySet()) {
            MapView.MapObjectInfo info = chr.mapView().get(oid);
            if (info == null || info.type() != MapObjectType.MONSTER) {
                continue;
            }
            final int mobId = info.id();
            for (MonsterDropEntry de : MonsterInformationProvider.getInstance().retrieveEffectiveDrop(mobId)) {
                double rate = chr.getCardRate(de.itemId);
                if (rate != 1.0) {
                    cardRates.add(new Battle.DropEntitlement.PerItemDropRate(mobId, de.itemId, rate));
                }
            }
        }

        return new Battle.DropEntitlement(dropRate, mesoRate, chr.getCardRate(0), cardRates);
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

        AssertUtil.isTrue(ret.skill != Rogue.LUCKY_SEVEN);
        AssertUtil.isTrue(ret.skill != NightWalker.LUCKY_SEVEN);
        AssertUtil.isTrue(ret.skill != NightLord.TRIPLE_THROW);

        AssertUtil.isTrue(ret.skill != DragonKnight.DRAGON_ROAR);

        AssertUtil.isTrue(ret.skill != NightLord.VENOMOUS_STAR);
        AssertUtil.isTrue(ret.skill != Shadower.VENOMOUS_STAB);


        ret.dmgCap = chr.calculateMaxBaseDamage(chr.getTotalWatk());

        BuffEffectData effect = null;
        AssertUtil.isTrue(ret.skill == 0);
        /*
        if (ret.skill != 0) {
            Skill skill = SkillFactory.getSkill(ret.skill);
            effect = skill.getEffect(ret.skilllevel);

            if (ret.skill == Hermit.SHADOW_MESO) {
                // Shadow Meso also has its own formula
                ret.dmgCap = effect.getMoneyCon() * 10;
                ret.dmgCap = (int) Math.floor(ret.dmgCap * 1.5);
            } else {
                // Normal damage formula for skills
                ret.dmgCap = ret.dmgCap * effect.getDamage() / 100;
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
            ret.dmgCap = (long) Math.ceil(ret.dmgCap * dmgBuff);
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
        ret.canCrit = false;   // 全量版 = 职业判定（弓/贼/夜行者/风灵/战神3-4/拳手），本版断言排除

        AssertUtil.isTrue(chr.getBuffEffect(EffectType.SHARP_EYES) == null);

        AssertUtil.isTrue(chr.getBuffEffect(EffectType.SHADOWPARTNER) == null);

        AssertUtil.isTrue(ret.skill == 0);
        /*
        if (ret.skill != 0) {
            int fixed = ret.getAttackEffect(chr, SkillFactory.getSkill(ret.skill)).getFixDamage();
            if (fixed > 0) {
                ret.dmgCap = fixed;
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
                            ret.dmgCap *= 1.5;
                        } else if (eff == ElementalEffectiveness.STRONG) {
                            //ret.dmgCap *= 0.5;
                        }
                    } else {
                        // Since we already know the skill has an elemental attribute, but we dont know if the monster is weak or not, lets
                        // take the safe approach and just assume they are weak.
                        ret.dmgCap *= 1.5;
                    }
                }
                if (ret.skill == FPWizard.POISON_BREATH || ret.skill == FPMage.POISON_MIST || ret.skill == FPArchMage.FIRE_DEMON || ret.skill == ILArchMage.ICE_DEMON) {
                    if (monster != null) {
                        // Turns out poison is completely server side, so I don't know why I added this. >.<
                        //ret.dmgCap = monster.getHp() / (70 - chr.getSkillLevel(skill));
                    }
                } else if (ret.skill == Hermit.SHADOW_WEB) {
                    if (monster != null) {
                        ret.dmgCap = monster.getHp() / (50 - chr.getSkillLevel(ret.skill));
                    }
                } else if (ret.skill == Hermit.SHADOW_MESO) {
                    if (monster != null) {
                        monster.debuffMob(Hermit.SHADOW_MESO);
                    }
                } else if (ret.skill == Aran.BODY_PRESSURE) {
                    if (monster != null) {
                        int bodyPressureDmg = (int) Math.ceil(monster.getMaxHp() * SkillFactory.getSkill(Aran.BODY_PRESSURE).getEffect(ret.skilllevel).getDamage() / 100.0);
                        if (bodyPressureDmg > ret.dmgCap) {
                            ret.dmgCap = bodyPressureDmg;
                        }
                    }
                }
            }
            */

            for (int j = 0; j < ret.numDamage; j++) {
                int damage = target.damages().get(j);
                long hitDmgMax = ret.dmgCap;

                AssertUtil.isTrue(ret.skill != Buccaneer.BARRAGE);
                AssertUtil.isTrue(ret.skill != ThunderBreaker.BARRAGE);

                AssertUtil.isTrue(ret.skill != Marksman.SNIPE);

                AssertUtil.isTrue(ret.skill != Beginner.BAMBOO_RAIN);
                AssertUtil.isTrue(ret.skill != Noblesse.BAMBOO_RAIN);
                AssertUtil.isTrue(ret.skill != Evan.BAMBOO_THRUST);
                AssertUtil.isTrue(ret.skill != Evan.BAMBOO_THRUST);

                AssertUtil.isTrue(ret.skill != Marksman.SNIPE);
                AssertUtil.isTrue(!ret.canCrit || damage <= hitDmgMax);
                /*
                if (ret.canCrit && damage > hitDmgMax) {
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

    /**
     * 一次近战攻击的 attacker 侧结算单（目标无关工作态）：由 {@link CloseRangeAttack} 申报
     * 构造，phase 1 的全部 attacker 侧推导与逐 hit 校验落在其上，终态即构造
     * {@link Battle.CloseRangeAttackIntent} 的全部信息源。成员判据：申报回填、伤害上限
     * 模型、逐 hit 标量进；目标/怪物知识（phase 2）与 actor 身份（intent 的 ref/cid）不进。
     */
    /**
     * 击杀结算（player actor 个人段，原 Monster.giveExpToCharacter 个人段平移）：
     * 权重已含 map 侧团队结算（死亡归属/份额/level split/MVP/SHOWDOWN），本域只做
     * 个人修正（Holy Symbol/rates/EXP buff/家族）→ 钳制舍入 → 写账（经验/装备经验/
     * 任务计数/勋章）。全自访（接收方 strand，域盖章自放行）。
     */
    @Override
    public void monsterKilled(int mobId, int mobLevel, float expWeight, float partyBonusWeight,
                              boolean white, boolean hasPartySharers, float showdownMult) {
        Character chr = owner;
        if (!chr.isAlive()) {
            return;
        }

        float personalExp = expWeight;
        float partyExp = partyBonusWeight;

        float mult = 1.0f;
        Integer holySymbol = chr.getBuffedValue(EffectType.HOLY_SYMBOL);
        if (holySymbol != null) {
            if (GameConfig.getServerBoolean("use_full_holy_symbol")) { // thanks Mordred, xinyifly, AyumiLove, andy33 for noticing HS hands out 20% of its potential on less than 3 players
                mult *= 1.0 + holySymbol.doubleValue() / 100.0;
            } else {
                mult *= 1.0 + holySymbol.doubleValue() / (hasPartySharers ? 100.0 : 500.0);
            }
        }
        mult *= showdownMult;   // 怪物 SHOWDOWN 状态倍率（map 域知识，随消息显形）

        personalExp *= mult * (chr.getExpRate() * chr.getMobExpRate());
        Integer expBonus = chr.getBuffedValue(EffectType.EXP_INCREASE);
        if (expBonus != null) {     // exp increase player buff found thanks to HighKey21
            personalExp += expBonus;
        }
        Integer expBuff = chr.getBuffedValue(EffectType.EXP_BUFF);
        if (expBuff != null) {
            personalExp *= 2;
        }
        if (chr.isFamilyBuff()) {
            personalExp *= chr.getFamilyExp();
        }
        int _personalExp = expValueToInteger(personalExp); // assuming no negative xp here

        partyExp *= mult * (chr.getExpRate() * chr.getMobExpRate());
        partyExp *= GameConfig.getServerFloat("party_bonus_exp_rate");
        int _partyExp = expValueToInteger(partyExp);

        chr.gainExp(_personalExp, _partyExp, true, false, white);
        chr.raiseQuestMobCount(mobId);
        VeteranHunterMedal.onMonsterKilled(chr, mobId, mobLevel);
        // 特级挑战勋章复用怪物死亡事件，在角色已接任务时写入个人击杀进度。
        SpecialChallengeMedal.onMonsterKilled(chr, mobId, mobLevel);
        chr.increaseEquipExp(_personalExp);
    }

    private static int expValueToInteger(double exp) {
        if (exp > Integer.MAX_VALUE) {
            exp = Integer.MAX_VALUE;
        } else if (exp < Integer.MIN_VALUE) {
            exp = Integer.MIN_VALUE;
        }

        return (int) Math.round(exp);    // operations on float point are not point-precise... thanks IxianMace for noticing -1 EXP gains
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
