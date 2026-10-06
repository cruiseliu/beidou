package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.Client;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MapId;
import org.gms.constants.id.MobId;
import org.gms.constants.skills.*;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.modules.battle.BattleModule;
import org.gms.remote.modules.battle.client.CloseRangeAttack;
import org.gms.scripting.AbstractPlayerInteraction;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
import org.gms.server.life.Element;
import org.gms.server.life.ElementalEffectiveness;
import org.gms.server.life.MobSkill;
import org.gms.server.life.MobSkillFactory;
import org.gms.server.life.MobSkillId;
import org.gms.server.life.MobSkillType;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterDropEntry;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.partyquest.Pyramid;
import org.gms.util.AssertUtil;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.gms.util.Randomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 战斗模块组件：CLOSE_RANGE_ATTACK 近战攻击的 gameplay 侧全量逻辑。
 *
 * <p>机械复制自 legacy CloseRangeDamageHandler + AbstractDealDamageHandler 的近战路径
 * （handler 体 / AttackInfo / 伤害上限管线 / applyAttack），语义逐行等价，仅入口适配与
 * 死代码省略：wire 解码上移 gms083 codec（本组件收语义载荷后重建 AttackInfo 并执行
 * 伤害上限管线）；currentServerTime 改直调 Server；已注释停用的距离检测主体与其专用
 * 死变量不随迁（legacy 侧保留）。S→C 面仍走 legacy PacketCreator / map 广播（未语义化，
 * strict 哨暂缓）。远程/魔法/召唤/触怪（同基类其余 4 个 handler）仍在 legacy，复制体不回流。
 */
class CharacterBattle implements BattleModule.Handler {

    private static final Logger log = LoggerFactory.getLogger(CharacterBattle.class);

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
        Client c = owner.getClient();

        AttackInfo attack = parseCloseRange(data, chr);

        if (chr.getBuffEffect(EffectType.MORPH) != null) {
            if (chr.getBuffEffect(EffectType.MORPH).isMorphWithoutAttack()) {
                // How are they attacking when the client won't let them?
                chr.getClient().disconnect(false, false);
                return;
            }
        }

        boolean pyramidSkill = MapId.isNettsPyramid(chr.getMap().getId()) && isRageOfPharaoh(attack.skill);
        if (pyramidSkill) {
            if (!(chr.getPartyQuest() instanceof Pyramid pyramid) || !pyramid.useSkill()) {
                return;
            }
        } else if (chr.getDojoEnergy() < 10000 && isBambooRain(attack.skill)) { // PE hacking or maybe just lagging
            return;
        }
        if (MapId.isDojo(chr.getMap().getId()) && attack.numAttacked > 0) {
            chr.setDojoEnergy(chr.getDojoEnergy() + GameConfig.getServerInt("dojo_energy_atk"));
            c.sendPacket(PacketCreator.getEnergy("energy", chr.getDojoEnergy()));
        }

        int numFinisherOrbs = 0;
        Integer comboBuff = chr.getBuffedValue(EffectType.COMBO);
        if (GameConstants.isFinisherSkill(attack.skill)) {
            if (comboBuff != null) {
                numFinisherOrbs = comboBuff - 1;
            }
            chr.handleOrbconsume();
        } else if (attack.numAttacked > 0) {
            if (attack.skill != 1111008 && comboBuff != null) {
                int orbcount = chr.getBuffedValue(EffectType.COMBO);
                int oid = chr.isCygnus() ? DawnWarrior.COMBO : Crusader.COMBO;
                int advcomboid = chr.isCygnus() ? DawnWarrior.ADVANCED_COMBO : Hero.ADVANCED_COMBO;
                Skill combo = SkillFactory.getSkill(oid);
                Skill advcombo = SkillFactory.getSkill(advcomboid);
                BuffEffectData ceffect;
                int advComboSkillLevel = chr.getSkillLevel(advcomboid);
                if (advComboSkillLevel > 0) {
                    ceffect = advcombo.getEffect(advComboSkillLevel);
                } else {
                    int comboLv = chr.getSkillLevel(oid);
                    if (comboLv <= 0 || chr.isGM()) {
                        comboLv = SkillFactory.getSkill(oid).getMaxLevel();
                    }

                    if (comboLv > 0) {
                        ceffect = combo.getEffect(comboLv);
                    } else {
                        ceffect = null;
                    }
                }
                if (ceffect != null) {
                    if (orbcount < ceffect.getX() + 1) {
                        int neworbcount = orbcount + 1;
                        if (advComboSkillLevel > 0 && ceffect.makeChanceResult()) {
                            if (neworbcount <= ceffect.getX()) {
                                neworbcount++;
                            }
                        }

                        int olv = chr.getSkillLevel(oid);
                        if (olv <= 0) {
                            olv = SkillFactory.getSkill(oid).getMaxLevel();
                        }

                        int duration = combo.getEffect(olv).getDuration();
                        List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.COMBO, neworbcount));
                        chr.setBuffedValue(EffectType.COMBO, neworbcount);
                        duration -= (int) (Server.getInstance().getCurrentTime() - chr.getBuffedStarttime(EffectType.COMBO));
                        c.sendPacket(PacketCreator.giveBuff(oid, duration, stat));
                        chr.getMap().broadcastMessage(chr, PacketCreator.giveForeignBuff(chr.getId(), stat), false);
                    }
                }
            } else if (chr.getSkillLevel(chr.isCygnus() ? 15100004 : 5110001) > 0 && (chr.getJob().isA(JobEnum.MARAUDER) || chr.getJob().isA(JobEnum.THUNDERBREAKER2))) {
                for (int i = 0; i < attack.numAttacked; i++) {
                    chr.handleEnergyChargeGain();
                }
            }
        }
        if (attack.numAttacked > 0 && attack.skill == DragonKnight.SACRIFICE) {
            int totDamageToOneMonster = 0; // sacrifice attacks only 1 mob with 1 attack
            final var dmgIt = attack.allDamage.values().iterator();
            if (dmgIt.hasNext()) {
                totDamageToOneMonster = dmgIt.next().get(0);
            }

            chr.safeAddHP(-1 * totDamageToOneMonster * attack.getAttackEffect(chr, null).getX() / 100);
        }
        if (attack.numAttacked > 0 && attack.skill == 1211002) {
            boolean advcharge_prob = false;
            int advcharge_level = chr.getSkillLevel(1220010);
            if (advcharge_level > 0) {
                advcharge_prob = SkillFactory.getSkill(1220010).getEffect(advcharge_level).makeChanceResult();
            }
            if (!advcharge_prob) {
                chr.cancelEffectFromBuffStat(EffectType.WK_CHARGE);
            }
        }
        int attackCount = 1;
        if (attack.skill != 0) {
            attackCount = attack.getAttackEffect(chr, null).getAttackCount();
        }
        if (numFinisherOrbs == 0 && GameConstants.isFinisherSkill(attack.skill)) {
            return;
        }
        if (isBambooRain(attack.skill)) { // bamboo
            if (chr.getDojoEnergy() < 10000) { // PE hacking or maybe just lagging
                return;
            }
            chr.setDojoEnergy(0);
            c.sendPacket(PacketCreator.getEnergy("energy", chr.getDojoEnergy()));
            c.sendPacket(PacketCreator.serverNotice(5, I18nUtil.getMessage("Dojo.secretSkill.energyReset")));
        } else if (attack.skill > 0) {
            Skill skill = SkillFactory.getSkill(attack.skill);
            int skillLevel = pyramidSkill ? 1 : chr.getSkillLevel(attack.skill);
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
        if ((chr.getSkillLevel(NightWalker.VANISH) > 0 || chr.getSkillLevel(Rogue.DARK_SIGHT) > 0) && chr.getBuffedValue(EffectType.DARKSIGHT) != null) {
            chr.cancelEffectFromBuffStat(EffectType.DARKSIGHT);
            chr.cancelBuffStats(EffectType.DARKSIGHT);
        } else if (chr.getSkillLevel(WindArcher.WIND_WALK) > 0 && chr.getBuffedValue(EffectType.WIND_WALK) != null) {
            chr.cancelEffectFromBuffStat(EffectType.WIND_WALK);
            chr.cancelBuffStats(EffectType.WIND_WALK);
        }

        // 伤害应用（player 域写复合体：buff/stats/弹药/装备经验）在 player strand 串行；
        // 内部 mob 写（Monster.damage）为既有跨界语义（与迁移前等价，阶段二收编）。
        // 中继广播异步交 map（可见性/他人流归 map 域；中继相对伤害包的跨线程序为
        // 接受的渲染级偏差，solo 无观察者）。
        applyAttack(attack, chr, attackCount);
        final Packet relay = PacketCreator.closeRangeAttack(chr, attack.skill, attack.skilllevel, attack.stance,
                attack.numAttackedAndDamage, attack.allDamage, attack.speed, attack.direction, attack.display);
        final MapleMap map = chr.getMap();
        map.post("close-range-relay", () -> map.broadcastMessage(chr, relay, false, true));
    }

    /**
     * 语义载荷 → AttackInfo（历史 parseDamage 近战路径的 role 拆分）：字段回填 +
     * 技能等级查表（含 PQ 技能豁免）+ 伤害上限管线与逐怪伤害改写。
     * MESO_EXPLOSION 无上限管线（历史 parse 同款早退）。
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

        if (ret.skill > 0) {
            ret.skilllevel = chr.getSkillLevel(ret.skill);
            if (ret.skilllevel == 0 && GameConstants.isPqSkillMap(chr.getMapId()) && GameConstants.isPqSkill(ret.skill)) {
                ret.skilllevel = 1;
            }
        }

        if (ret.skill == ChiefBandit.MESO_EXPLOSION) {
            for (CloseRangeAttack.Target t : data.targets()) {
                ret.allDamage.put(t.oid(), t.damages() == null ? null : new ArrayList<>(t.damages()));
            }
            for (Integer mesoid : data.mesoOids()) {
                ret.allDamage.put(mesoid, null);
            }
            return ret;
        }

        // Find the base damage to base further calculations on.
        // Several skills have their own formula in this section.
        long calcDmgMax;

        if (ret.skill == Rogue.LUCKY_SEVEN || ret.skill == NightWalker.LUCKY_SEVEN || ret.skill == NightLord.TRIPLE_THROW) {
            calcDmgMax = (long) ((chr.getTotalLuk() * 5) * Math.ceil(chr.getTotalWatk() / 100.0));
        } else if (ret.skill == DragonKnight.DRAGON_ROAR) {
            calcDmgMax = (long) ((chr.getTotalStr() * 4 + chr.getTotalDex()) * Math.ceil(chr.getTotalWatk() / 100.0));
        } else if (ret.skill == NightLord.VENOMOUS_STAR || ret.skill == Shadower.VENOMOUS_STAB) {
            calcDmgMax = (long) (Math.ceil((18.5 * (chr.getTotalStr() + chr.getTotalLuk()) + chr.getTotalDex() * 2) / 100.0) * chr.calculateMaxBaseDamage(chr.getTotalWatk()));
        } else {
            calcDmgMax = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        }

        BuffEffectData effect = null;
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

        Integer comboBuff = chr.getBuffedValue(EffectType.COMBO);
        if (comboBuff != null && comboBuff > 0) {
            int oid = chr.isCygnus() ? DawnWarrior.COMBO : Crusader.COMBO;
            int advcomboid = chr.isCygnus() ? DawnWarrior.ADVANCED_COMBO : Hero.ADVANCED_COMBO;

            if (comboBuff > 6) {
                // Advanced Combo
                BuffEffectData ceffect = SkillFactory.getSkill(advcomboid).getEffect(chr.getSkillLevel(advcomboid));
                calcDmgMax = (long) Math.floor(calcDmgMax * (ceffect.getDamage() + 50) / 100 + 0.20 + (comboBuff - 5) * 0.04);
            } else {
                // Normal Combo
                int skillLv = chr.getSkillLevel(oid);
                if (skillLv <= 0 || chr.isGM()) {
                    skillLv = SkillFactory.getSkill(oid).getMaxLevel();
                }

                if (skillLv > 0) {
                    BuffEffectData ceffect = SkillFactory.getSkill(oid).getEffect(skillLv);
                    calcDmgMax = (long) Math.floor(calcDmgMax * (ceffect.getDamage() + 50) / 100 + Math.floor((comboBuff - 1) * (skillLv / 6)) / 100);
                }
            }

            if (GameConstants.isFinisherSkill(ret.skill)) {
                // Finisher skills do more damage based on how many orbs the player has.
                int orbs = comboBuff - 1;
                if (orbs == 2) {
                    calcDmgMax *= 1.2;
                } else if (orbs == 3) {
                    calcDmgMax *= 1.54;
                } else if (orbs == 4) {
                    calcDmgMax *= 2;
                } else if (orbs >= 5) {
                    calcDmgMax *= 2.5;
                }
            }
        }

        if (chr.getEnergyBar() == 15000) {
            int energycharge = chr.isCygnus() ? ThunderBreaker.ENERGY_CHARGE : Marauder.ENERGY_CHARGE;
            BuffEffectData ceffect = SkillFactory.getSkill(energycharge).getEffect(chr.getSkillLevel(energycharge));
            calcDmgMax *= (100 + ceffect.getDamage()) / 100;
        }

        int bonusDmgBuff = 100;
        for (BuffEffectData buffEffect : chr.getAllBuffs()) {
            int bonusDmg = buffEffect.getDamage() - 100;
            bonusDmgBuff += bonusDmg;
        }

        if (bonusDmgBuff != 100) {
            float dmgBuff = bonusDmgBuff / 100.0f;
            calcDmgMax = (long) Math.ceil(calcDmgMax * dmgBuff);
        }

        if (chr.getMapId() >= MapId.ARAN_TUTORIAL_START && chr.getMapId() <= MapId.ARAN_TUTORIAL_MAX) {
            calcDmgMax += 80000; // Aran Tutorial.
        }

        boolean canCrit = chr.getJob().isA((JobEnum.BOWMAN)) || chr.getJob().isA(JobEnum.THIEF) || chr.getJob().isA(JobEnum.NIGHTWALKER1) || chr.getJob().isA(JobEnum.WINDARCHER1) || chr.getJob() == JobEnum.ARAN3 || chr.getJob() == JobEnum.ARAN4 || chr.getJob() == JobEnum.MARAUDER || chr.getJob() == JobEnum.BUCCANEER;

        BuffEffectData sharpEyesEffect = chr.getBuffEffect(EffectType.SHARP_EYES);
        if (sharpEyesEffect != null) {
            // Any class that has sharp eyes can crit. Also, since it stacks with normal crit go ahead
            // and calc it in.
            canCrit = true;
            // 精确火眼按照当前等级计算伤害，而不是直接粗暴的取满级1.4，如果技改了wz，也能完全适配
            calcDmgMax = (long) Math.ceil(sharpEyesEffect.getY() / 100.0 * calcDmgMax);
        }

        boolean shadowPartner = chr.getBuffEffect(EffectType.SHADOWPARTNER) != null;

        if (ret.skill != 0) {
            int fixed = ret.getAttackEffect(chr, SkillFactory.getSkill(ret.skill)).getFixDamage();
            if (fixed > 0) {
                calcDmgMax = fixed;
            }
        }

        for (CloseRangeAttack.Target target : data.targets()) {
            int oid = target.oid();
            List<Integer> allDamageNumbers = new ArrayList<>();
            Monster monster = chr.getMap().getMonsterByOid(oid);

            if (chr.getBuffEffect(EffectType.WK_CHARGE) != null) {
                // Charge, so now we need to check elemental effectiveness
                int sourceID = chr.getBuffSource(EffectType.WK_CHARGE);
                int level = chr.getBuffedValue(EffectType.WK_CHARGE);
                if (monster != null) {
                    if (sourceID == WhiteKnight.BW_FIRE_CHARGE || sourceID == WhiteKnight.SWORD_FIRE_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.FIRE) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.05 + level * 0.015;
                        }
                    } else if (sourceID == WhiteKnight.BW_ICE_CHARGE || sourceID == WhiteKnight.SWORD_ICE_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.ICE) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.05 + level * 0.015;
                        }
                    } else if (sourceID == WhiteKnight.BW_LIT_CHARGE || sourceID == WhiteKnight.SWORD_LIT_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.LIGHTING) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.05 + level * 0.015;
                        }
                    } else if (sourceID == Paladin.BW_HOLY_CHARGE || sourceID == Paladin.SWORD_HOLY_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.HOLY) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.2 + level * 0.015;
                        }
                    }
                } else {
                    // Since we already know the skill has an elemental attribute, but we dont know if the monster is weak or not, lets
                    // take the safe approach and just assume they are weak.
                    calcDmgMax *= 1.5;
                }
            }

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

            for (int j = 0; j < ret.numDamage; j++) {
                int damage = target.damages().get(j);
                long hitDmgMax = calcDmgMax;
                if (ret.skill == Buccaneer.BARRAGE || ret.skill == ThunderBreaker.BARRAGE) {
                    if (j > 3) {
                        hitDmgMax *= Math.pow(2, (j - 3));
                    }
                }
                if (shadowPartner) {
                    // For shadow partner, the second half of the hits only do 50% damage. So calc that
                    // in for the crit effects.
                    if (j >= ret.numDamage / 2) {
                        hitDmgMax *= 0.5;
                    }
                }

                if (ret.skill == Marksman.SNIPE) {
                    damage = 195000 + Randomizer.nextInt(5000);
                    hitDmgMax = 200000;
                } else if (ret.skill == Beginner.BAMBOO_RAIN || ret.skill == Noblesse.BAMBOO_RAIN || ret.skill == Evan.BAMBOO_THRUST || ret.skill == Legend.BAMBOO_THRUST) {
                    hitDmgMax = 82569000; // 30% of Max HP of strongest Dojo boss
                }

                if (ret.skill == Marksman.SNIPE || (canCrit && damage > hitDmgMax)) {
                    // If the skill is a crit, inverse the damage to make it show up on clients.
                    damage = -Integer.MAX_VALUE + damage - 1;
                }

                if (effect != null) {
                    int maxattack = Math.max(effect.getBulletCount(), effect.getAttackCount());
                    if (shadowPartner) {
                        maxattack = maxattack * 2;
                    }
                }

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
    }

    public static void applyAttack(AttackInfo attack, final Character player, int attackCount) {
        final MapleMap map = player.getMap();

        Skill theSkill = null;
        BuffEffectData attackEffect = null;
        final int job = player.getJob().getId();
        try {
            if (attack.skill != 0) {
                theSkill = SkillFactory.getSkill(attack.skill); // thanks Conrad for noticing some Aran skills not consuming MP
                attackEffect = attack.getAttackEffect(player, theSkill); //returns back the player's attack effect so we are gucci
                if (attackEffect == null) {
                    player.sendPacket(PacketCreator.enableActions());
                    return;
                }

                int mobCount = attackEffect.getMobCount();
                if (attack.skill != Cleric.HEAL) {
                    if (player.isAlive()) {
                        if (attack.skill == Aran.BODY_PRESSURE || attack.skill == Marauder.ENERGY_CHARGE || attack.skill == ThunderBreaker.ENERGY_CHARGE) {  // thanks IxianMace for noticing Energy Charge skills refreshing on touch
                            // prevent touch dmg skills refreshing
                        } else if (attack.skill == DawnWarrior.FINAL_ATTACK || attack.skill == WindArcher.FINAL_ATTACK) {
                            // prevent cygnus FA refreshing
                            mobCount = 15;
                        } else if (attack.skill == NightWalker.POISON_BOMB) {// Poison Bomb
                            attackEffect.applyTo(player, new Point(attack.position.x, attack.position.y));
                        } else {
                            attackEffect.applyTo(player);

                            if (attack.skill == Page.FINAL_ATTACK_BW || attack.skill == Page.FINAL_ATTACK_SWORD || attack.skill == Fighter.FINAL_ATTACK_SWORD
                                    || attack.skill == Fighter.FINAL_ATTACK_AXE || attack.skill == Spearman.FINAL_ATTACK_SPEAR || attack.skill == Spearman.FINAL_ATTACK_POLEARM
                                    || attack.skill == Hunter.FINAL_ATTACK || attack.skill == Crossbowman.FINAL_ATTACK) {

                                mobCount = 15;//:(
                            } else if (attack.skill == Aran.HIDDEN_FULL_DOUBLE || attack.skill == Aran.HIDDEN_FULL_TRIPLE || attack.skill == Aran.HIDDEN_OVER_DOUBLE || attack.skill == Aran.HIDDEN_OVER_TRIPLE) {
                                mobCount = 12;
                            }
                        }
                    } else {
                        player.sendPacket(PacketCreator.enableActions());
                    }
                }

                if (attack.numAttacked > mobCount) {
                    return;
                }
            }
            if (!player.isAlive()) {
                return;
            }

            final boolean skipDistanceHack = shouldSkipDistanceHackCheck(attack.skill, attackEffect);
            final boolean isChainLightning = attack.skill == ILArchMage.CHAIN_LIGHTNING;
            boolean chainLightningCheckedFirst = false;

            int totDamage = 0;
            if (attack.skill == ChiefBandit.MESO_EXPLOSION) {
                int delay = 0;
                for (Integer oned : attack.allDamage.keySet()) {
                    MapObject mapobject = map.getMapObject(oned);
                    if (mapobject != null && mapobject.getType() == MapObjectType.ITEM) {
                        final MapItem mapitem = (MapItem) mapobject;
                        if (mapitem.getMeso() == 0) { //Maybe it is possible some how?
                            return;
                        }

                        mapitem.lockItem();
                        try {
                            if (mapitem.isPickedUp()) {
                                return;
                            }
                            TimerManager.getInstance().schedule(() -> {
                                mapitem.lockItem();
                                try {
                                    if (mapitem.isPickedUp()) {
                                        return;
                                    }
                                    map.pickItemDrop(PacketCreator.removeItemFromMap(mapitem.getObjectId(), 4, 0), mapitem);
                                } finally {
                                    mapitem.unlockItem();
                                }
                            }, delay);
                            delay += 100;
                        } finally {
                            mapitem.unlockItem();
                        }
                    } else if (mapobject != null && mapobject.getType() != MapObjectType.MONSTER) {
                        return;
                    }
                }
            }
            for (Integer oned : attack.allDamage.keySet()) {
                final Monster monster = map.getMonsterByOid(oned);
                if (monster != null) {
                    if (!skipDistanceHack) {
                        if (isChainLightning) {
                            // 链式技能后续目标来源于跳跃，不以玩家距离判断
                            if (!chainLightningCheckedFirst) {
                                chainLightningCheckedFirst = true;
                            }
                        }
                    }

                    int totDamageToOneMonster = 0;
                    List<Integer> onedList = attack.allDamage.get(oned);

                    if (attack.magic) { // thanks BHB, Alex (CanIGetaPR) for noticing no immunity status check here
                        if (monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY)) {
                            Collections.fill(onedList, 1);
                        }
                    } else {
                        if (monster.isBuffed(MonsterStatus.WEAPON_IMMUNITY)) {
                            Collections.fill(onedList, 1);
                        }
                    }

                    if (MobId.isDojoBoss(monster.getId())) {
                        if (attack.skill == 1009 || attack.skill == 10001009 || attack.skill == 20001009) {
                            int dmgLimit = (int) Math.ceil(0.3 * monster.getMaxHp());
                            List<Integer> _onedList = new LinkedList<>();
                            for (Integer i : onedList) {
                                _onedList.add(i < dmgLimit ? i : dmgLimit);
                            }

                            onedList = _onedList;
                        }
                    }

                    for (Integer eachd : onedList) {
                        if (eachd < 0) {
                            eachd += Integer.MAX_VALUE;
                        }
                        totDamageToOneMonster += eachd;
                    }
                    totDamage += totDamageToOneMonster;
                    monster.aggroMonsterDamage(player, totDamageToOneMonster);
                    if (player.getBuffedValue(EffectType.PICKPOCKET) != null && (attack.skill == 0 || attack.skill == Rogue.DOUBLE_STAB || attack.skill == Bandit.SAVAGE_BLOW || attack.skill == ChiefBandit.ASSAULTER || attack.skill == ChiefBandit.BAND_OF_THIEVES || attack.skill == Shadower.ASSASSINATE || attack.skill == Shadower.TAUNT || attack.skill == Shadower.BOOMERANG_STEP)) {
                        Skill pickpocket = SkillFactory.getSkill(ChiefBandit.PICKPOCKET);
                        int picklv = (player.isGM()) ? pickpocket.getMaxLevel() : player.getSkillLevel(ChiefBandit.PICKPOCKET);
                        if (picklv > 0) {
                            int delay = 0;
                            final int maxmeso = player.getBuffedValue(EffectType.PICKPOCKET);
                            for (Integer eachd : onedList) {
                                eachd += Integer.MAX_VALUE;

                                if (pickpocket.getEffect(picklv).makeChanceResult()) {
                                    final int eachdf;
                                    if (eachd < 0) {
                                        eachdf = eachd + Integer.MAX_VALUE;
                                    } else {
                                        eachdf = eachd;
                                    }

                                    TimerManager.getInstance().schedule(() -> map.spawnMesoDrop(Math.min((int) Math.max(((double) eachdf / (double) 20000) * (double) maxmeso, 1), maxmeso), new Point((int) (monster.getPosition().getX() + Randomizer.nextInt(100) - 50), (int) (monster.getPosition().getY())), monster, player.ref(), true, (byte) 2), delay);
                                    delay += 100;
                                }
                            }
                        }
                    } else if (attack.skill == Marauder.ENERGY_DRAIN || attack.skill == ThunderBreaker.ENERGY_DRAIN || attack.skill == NightWalker.VAMPIRE || attack.skill == Assassin.DRAIN) {
                        player.addHP(Math.min(monster.getMaxHp(), Math.min((int) ((double) totDamage * (double) SkillFactory.getSkill(attack.skill).getEffect(player.getSkillLevel(attack.skill)).getX() / 100.0), player.getCurrentMaxHp() / 2)));
                    } else if (attack.skill == Bandit.STEAL) {
                        Skill steal = SkillFactory.getSkill(Bandit.STEAL);
                        if (monster.getStolen().size() < 1) { // One steal per mob <3
                            if (steal.getEffect(player.getSkillLevel(Bandit.STEAL)).makeChanceResult()) {
                                monster.addStolen(0);

                                MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
                                List<Integer> dropPool = mi.retrieveDropPool(monster.getId());
                                if (dropPool != null && !dropPool.isEmpty()) {
                                    int rndPool = (int) Math.floor(Math.random() * dropPool.get(dropPool.size() - 1));

                                    int i = 0;
                                    while (rndPool >= dropPool.get(i)) {
                                        i++;
                                    }

                                    List<MonsterDropEntry> toSteal = new ArrayList<>();
                                    toSteal.add(mi.retrieveDrop(monster.getId()).get(i));

                                    map.dropItemsFromMonster(toSteal, player.ref(), monster);
                                    monster.addStolen(toSteal.get(0).itemId);
                                }
                            }
                        }
                    } else if (attack.skill == FPArchMage.FIRE_DEMON) {
                        long duration = SECONDS.toMillis(SkillFactory.getSkill(FPArchMage.FIRE_DEMON).getEffect(player.getSkillLevel(FPArchMage.FIRE_DEMON)).getDuration());
                        monster.setTempEffectiveness(Element.ICE, ElementalEffectiveness.WEAK, duration);
                    } else if (attack.skill == ILArchMage.ICE_DEMON) {
                        long duration = SECONDS.toMillis(SkillFactory.getSkill(ILArchMage.ICE_DEMON).getEffect(player.getSkillLevel(ILArchMage.ICE_DEMON)).getDuration());
                        monster.setTempEffectiveness(Element.FIRE, ElementalEffectiveness.WEAK, duration);
                    } else if (attack.skill == Outlaw.HOMING_BEACON || attack.skill == Corsair.BULLSEYE) {
                        BuffEffectData beacon = SkillFactory.getSkill(attack.skill).getEffect(player.getSkillLevel(attack.skill));
                        beacon.applyBeaconBuff(player, monster.getObjectId());
                    } else if (attack.skill == Outlaw.FLAME_THROWER) {
                        if (!monster.isBoss()) {
                            Skill type = SkillFactory.getSkill(Outlaw.FLAME_THROWER);
                            if (player.getSkillLevel(Outlaw.FLAME_THROWER) > 0) {
                                BuffEffectData DoT = type.getEffect(player.getSkillLevel(Outlaw.FLAME_THROWER));
                                MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), type, null, false);
                                monster.applyStatus(player, monsterStatusEffect, true, DoT.getDuration(), false);
                            }
                        }
                    }

                    if (player.isAran()) {
                        if (player.getBuffedValue(EffectType.WK_CHARGE) != null) {
                            Skill snowCharge = SkillFactory.getSkill(Aran.SNOW_CHARGE);
                            if (totDamageToOneMonster > 0) {
                                MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.SPEED, snowCharge.getEffect(player.getSkillLevel(Aran.SNOW_CHARGE)).getX()), snowCharge, null, false);
                                long duration = SECONDS.toMillis(snowCharge.getEffect(player.getSkillLevel(Aran.SNOW_CHARGE)).getY());
                                monster.applyStatus(player, monsterStatusEffect, false, duration);
                            }
                        }
                    }
                    if (player.getBuffedValue(EffectType.HAMSTRING) != null) {
                        Skill hamstring = SkillFactory.getSkill(Bowmaster.HAMSTRING);
                        if (hamstring.getEffect(player.getSkillLevel(Bowmaster.HAMSTRING)).makeChanceResult()) {
                            MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.SPEED, hamstring.getEffect(player.getSkillLevel(Bowmaster.HAMSTRING)).getX()), hamstring, null, false);
                            long duration = SECONDS.toMillis(hamstring.getEffect(player.getSkillLevel(Bowmaster.HAMSTRING)).getY());
                            monster.applyStatus(player, monsterStatusEffect, false, duration);
                        }
                    }
                    if (player.getBuffedValue(EffectType.SLOW) != null) {
                        Skill slow = SkillFactory.getSkill(Evan.SLOW);
                        if (slow.getEffect(player.getSkillLevel(Evan.SLOW)).makeChanceResult()) {
                            MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.SPEED, slow.getEffect(player.getSkillLevel(Evan.SLOW)).getX()), slow, null, false);
                            long duration = MINUTES.toMillis(slow.getEffect(player.getSkillLevel(Evan.SLOW)).getY());
                            monster.applyStatus(player, monsterStatusEffect, false, duration);
                        }
                    }
                    if (player.getBuffedValue(EffectType.BLIND) != null) {
                        Skill blind = SkillFactory.getSkill(Marksman.BLIND);
                        if (blind.getEffect(player.getSkillLevel(Marksman.BLIND)).makeChanceResult()) {
                            MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.ACC, blind.getEffect(player.getSkillLevel(Marksman.BLIND)).getX()), blind, null, false);
                            long duration = SECONDS.toMillis(blind.getEffect(player.getSkillLevel(Marksman.BLIND)).getY());
                            monster.applyStatus(player, monsterStatusEffect, false, duration);
                        }
                    }
                    if (job == 121 || job == 122) {
                        for (int charge = 1211005; charge < 1211007; charge++) {
                            Skill chargeSkill = SkillFactory.getSkill(charge);
                            if (player.isBuffFrom(EffectType.WK_CHARGE, chargeSkill)) {
                                if (totDamageToOneMonster > 0) {
                                    if (charge == WhiteKnight.BW_ICE_CHARGE || charge == WhiteKnight.SWORD_ICE_CHARGE) {
                                        monster.setTempEffectiveness(Element.ICE, ElementalEffectiveness.WEAK, chargeSkill.getEffect(player.getSkillLevel(charge)).getY() * 1000);
                                        // 修复冰技能不冰怪的问题，关键是冰和火都没有对应的异常状态，对应的异常只有冻结。如果这里把ICE改了，那火怎么办？所以，还是先注释掉。
//                                        MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.FREEZE, chargeSkill.getEffect(player.getSkillLevel(chargeSkill)).getX()), chargeSkill, null, false);
//                                        long duration = SECONDS.toMillis(chargeSkill.getEffect(player.getSkillLevel(chargeSkill)).getY());
//                                        monster.applyStatus(player, monsterStatusEffect, false, duration);
                                        break;
                                    }
                                    if (charge == WhiteKnight.BW_FIRE_CHARGE || charge == WhiteKnight.SWORD_FIRE_CHARGE) {
                                        monster.setTempEffectiveness(Element.FIRE, ElementalEffectiveness.WEAK, chargeSkill.getEffect(player.getSkillLevel(charge)).getY() * 1000);
                                        break;
                                    }
                                }
                            }
                        }
                        if (job == 122) {
                            for (int charge = 1221003; charge < 1221004; charge++) {
                                Skill chargeSkill = SkillFactory.getSkill(charge);
                                if (player.isBuffFrom(EffectType.WK_CHARGE, chargeSkill)) {
                                    if (totDamageToOneMonster > 0) {
                                        monster.setTempEffectiveness(Element.HOLY, ElementalEffectiveness.WEAK, chargeSkill.getEffect(player.getSkillLevel(charge)).getY() * 1000);
                                        break;
                                    }
                                }
                            }
                        }
                    } else if (player.getBuffedValue(EffectType.COMBO_DRAIN) != null) {
                        Skill skill;
                        if (player.getBuffedValue(EffectType.COMBO_DRAIN) != null) {
                            skill = SkillFactory.getSkill(21100005);
                            player.addHP(((totDamage * skill.getEffect(player.getSkillLevel(21100005)).getX()) / 100));
                        }
                    } else if (job == 412 || job == 422 || job == 1411) {
                        Skill type = SkillFactory.getSkill(player.getJob().getId() == 412 ? 4120005 : (player.getJob().getId() == 1411 ? 14110004 : 4220005));
                        int venomSkillId = player.getJob().getId() == 412 ? 4120005 : (player.getJob().getId() == 1411 ? 14110004 : 4220005);
                        if (player.getSkillLevel(venomSkillId) > 0) {
                            BuffEffectData venomEffect = type.getEffect(player.getSkillLevel(venomSkillId));
                            for (int i = 0; i < attackCount; i++) {
                                if (venomEffect.makeChanceResult()) {
                                    if (monster.getVenomMulti() < 3) {
                                        monster.setVenomMulti((monster.getVenomMulti() + 1));
                                        MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), type, null, false);
                                        monster.applyStatus(player, monsterStatusEffect, false, venomEffect.getDuration(), true);
                                    }
                                }
                            }
                        }
                    } else if (job >= 311 && job <= 322) {
                        if (!monster.isBoss()) {
                            Skill mortalBlow;
                            if (job == 311 || job == 312) {
                                mortalBlow = SkillFactory.getSkill(Ranger.MORTAL_BLOW);
                            } else {
                                mortalBlow = SkillFactory.getSkill(Sniper.MORTAL_BLOW);
                            }

                            int skillLevel = player.getSkillLevel(mortalBlow.getId());
                            if (skillLevel > 0) {
                                BuffEffectData mortal = mortalBlow.getEffect(skillLevel);
                                if (monster.getHp() <= (monster.getStats().getHp() * mortal.getX()) / 100) {
                                    if (Randomizer.rand(1, 100) <= mortal.getY()) {
                                        map.damageMonster(player.ref(), monster, Integer.MAX_VALUE);  // thanks Conrad for noticing reduced EXP gain from skill kill
                                    }
                                }
                            }
                        }
                    }
                    if (attack.skill != 0) {
                        if (attackEffect.getFixDamage() != -1) {
                            int threeSnailsId = player.getJobType() * 10000000 + 1000;
                            if (attack.skill == threeSnailsId) {
                                if (GameConfig.getServerBoolean("use_ultra_three_snails")) {
                                    int skillLv = player.getSkillLevel(threeSnailsId);

                                    if (skillLv > 0) {
                                        AbstractPlayerInteraction api = player.getAbstractPlayerInteraction();

                                        int shellId = switch (skillLv) {
                                            case 1 -> ItemId.SNAIL_SHELL;
                                            case 2 -> ItemId.BLUE_SNAIL_SHELL;
                                            default -> ItemId.RED_SNAIL_SHELL;
                                        };

                                        if (api.haveItem(shellId, 1)) {
                                            api.gainItem(shellId, (short) -1, false);
                                            totDamageToOneMonster *= player.getLevel();
                                        } else {
                                            player.dropMessage(5, "你的蜗牛壳已经用完了，无法使用蜗牛投掷术。");  //蜗牛壳消耗完了
                                            totDamageToOneMonster = 0;
                                        }
                                    } else {
                                        totDamageToOneMonster = 0;
                                    }
                                }
                            }
                        }
                    }
                    if (totDamageToOneMonster > 0 && attackEffect != null) {
                        Map<MonsterStatus, Integer> attackEffectStati = attackEffect.getMonsterStati();
                        if (!attackEffectStati.isEmpty()) {
                            if (attackEffect.makeChanceResult()) {
                                monster.applyStatus(player, new MonsterStatusEffect(attackEffectStati, theSkill, null, false), attackEffect.isPoison(), attackEffect.getDuration());
                            }
                        }
                    }
                    if (attack.skill == Paladin.HEAVENS_HAMMER) {
                        if (!monster.isBoss()) {
                            damageMonsterWithSkill(player, map, monster, monster.getHp() - 1, attack.skill, 1777);
                        } else {
                            int HHDmg = (player.calculateMaxBaseDamage(player.getTotalWatk()) * (SkillFactory.getSkill(Paladin.HEAVENS_HAMMER).getEffect(player.getSkillLevel(Paladin.HEAVENS_HAMMER)).getDamage() / 100));
                            damageMonsterWithSkill(player, map, monster, (int) (Math.floor(Math.random() * (HHDmg / 5) + HHDmg * .8)), attack.skill, 1777);
                        }
                    } else if (attack.skill == Aran.COMBO_TEMPEST) {
                        if (!monster.isBoss()) {
                            damageMonsterWithSkill(player, map, monster, monster.getHp(), attack.skill, 0);
                        } else {
                            int TmpDmg = (player.calculateMaxBaseDamage(player.getTotalWatk()) * (SkillFactory.getSkill(Aran.COMBO_TEMPEST).getEffect(player.getSkillLevel(Aran.COMBO_TEMPEST)).getDamage() / 100));
                            damageMonsterWithSkill(player, map, monster, (int) (Math.floor(Math.random() * (TmpDmg / 5) + TmpDmg * .8)), attack.skill, 0);
                        }
                    } else {
                        if (attack.skill == Aran.BODY_PRESSURE) {
                            map.broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), totDamageToOneMonster));
                        }

                        map.damageMonster(player.ref(), monster, totDamageToOneMonster);
                    }
                    if (monster.isBuffed(MonsterStatus.WEAPON_REFLECT) && !attack.magic) {
                        for (MobSkillId msId : monster.getSkills()) {
                            if (msId.type() == MobSkillType.PHYSICAL_AND_MAGIC_COUNTER) {
                                MobSkill toUse = MobSkillFactory.getMobSkillOrThrow(MobSkillType.PHYSICAL_AND_MAGIC_COUNTER, msId.level());
                                player.addHP(-toUse.getX());
                                map.broadcastMessage(player, PacketCreator.damagePlayer(0, monster.getId(), player.getId(), toUse.getX(), 0, 0, false, 0, true, monster.getObjectId(), 0, 0), true);
                            }
                        }
                    }
                    if (monster.isBuffed(MonsterStatus.MAGIC_REFLECT) && attack.magic) {
                        for (MobSkillId msId : monster.getSkills()) {
                            if (msId.type() == MobSkillType.PHYSICAL_AND_MAGIC_COUNTER) {
                                MobSkill toUse = MobSkillFactory.getMobSkillOrThrow(MobSkillType.PHYSICAL_AND_MAGIC_COUNTER, msId.level());
                                player.addHP(-toUse.getY());
                                map.broadcastMessage(player, PacketCreator.damagePlayer(0, monster.getId(), player.getId(), toUse.getY(), 0, 0, false, 0, true, monster.getObjectId(), 0, 0), true);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void damageMonsterWithSkill(final Character attacker, final MapleMap map, final Monster monster, final int damage, int skillid, int fixedTime) {
        int animationTime;

        if (fixedTime == 0) {
            animationTime = SkillFactory.getSkill(skillid).getAnimationTime();
        } else {
            animationTime = fixedTime;
        }

        if (animationTime > 0) { // be sure to only use LIMITED ATTACKS with animation time here
            TimerManager.getInstance().schedule(() -> {
                map.broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), damage), monster.getPosition());
                map.damageMonster(attacker.ref(), monster, damage);
            }, animationTime);
        } else {
            map.broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), damage), monster.getPosition());
            map.damageMonster(attacker.ref(), monster, damage);
        }
    }

    /**
     * 按客户端 WZ 语义决定是否跳过通用 DISTANCE_HACK 判定。
     * 仅对明确不是“空间攻击框”语义的技能做豁免，避免误伤正常近战技能。
     * （距离检测主体在历史代码中已注释停用，此处保留豁免计算骨架，与 legacy 等价。）
     */
    private static boolean shouldSkipDistanceHackCheck(int skillId, BuffEffectData attackEffect) {
        return isFullScreenDistanceExempt(skillId)
                || isNonSpatialAttackSkill(skillId, attackEffect)
                || isPiercingProjectileWithoutAttackBox(skillId, attackEffect);
    }

    /**
     * 全屏/大范围技能跳过距离检测
     */
    private static boolean isFullScreenDistanceExempt(int skillId) {
        return skillId == Bishop.GENESIS
                || skillId == ILArchMage.BLIZZARD
                || skillId == FPArchMage.METEOR_SHOWER
                || skillId == BlazeWizard.METEOR_SHOWER;
    }

    /**
     * Energy Charge 在 Skill.wz 中没有 level/lt、level/rb，属于蓄能/状态触发语义，
     * 不应按普通近战攻击框进入距离外挂判定。
     */
    private static boolean isNonSpatialAttackSkill(int skillId, BuffEffectData attackEffect) {
        if (attackEffect != null && attackEffect.hasBoundingBox()) {
            return false;
        }

        return skillId == Marauder.ENERGY_CHARGE || skillId == ThunderBreaker.ENERGY_CHARGE;
    }

    /**
     * Avenger has no level/lt or level/rb in Skill.wz; the client sends hits from its piercing projectile path.
     * The generic distance check can skip valid hits when both the skill attack box and monster bbox are unavailable.
     */
    private static boolean isPiercingProjectileWithoutAttackBox(int skillId, BuffEffectData attackEffect) {
        if (attackEffect != null && attackEffect.hasBoundingBox()) {
            return false;
        }

        return skillId == Hermit.AVENGER || skillId == NightWalker.AVENGER;
    }

    private boolean isBambooRain(int skillId) {
        return skillId % 10000000 == 1009;
    }

    private boolean isRageOfPharaoh(int skillId) {
        return skillId % 10000000 == 1020;
    }
}
