package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.status.MonsterStatus;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.MapId;
import org.gms.constants.id.MobId;
import org.gms.constants.skills.*;
import org.gms.net.packet.Packet;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.modules.battle.BattleModule;
import org.gms.remote.modules.battle.client.CloseRangeAttack;
import org.gms.server.BuffEffectData;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.util.AssertUtil;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        AssertUtil.isTrue(!MapId.isNettsPyramid(chr.getMap().getId()));
        AssertUtil.isTrue(!MapId.isDojo(chr.getMap().getId()));

        AssertUtil.isTrue(!GameConstants.isFinisherSkill(attack.skill));
        AssertUtil.isTrue(chr.getBuffedValue(EffectType.COMBO) == null);

        AssertUtil.isTrue(chr.getSkillLevel(15100004) == 0);
        AssertUtil.isTrue(chr.getSkillLevel(5110001) == 0);

        AssertUtil.isTrue(attack.skill != DragonKnight.SACRIFICE);

        AssertUtil.isTrue(attack.skill != 1211002);

        AssertUtil.isTrue(attack.skill == 0);
        int attackCount = 1;
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


        // 伤害应用（player 域写复合体：buff/stats/弹药/装备经验）在 player strand 串行；
        // 内部 mob 写（Monster.damage）为既有跨界语义（与迁移前等价，阶段二收编）。
        // 中继广播异步交 map（可见性/他人流归 map 域；中继相对伤害包的跨线程序为
        // 接受的渲染级偏差，solo 无观察者）。
        applyAttack(attack, chr, attackCount);
        final Packet relay = PacketCreator.closeRangeAttack(
            chr,
            attack.skill,
            attack.skilllevel,
            attack.stance,
            attack.numAttackedAndDamage,
            attack.allDamage,
            attack.speed,
            attack.direction,
            attack.display
        );
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

        BuffEffectData attackEffect = null;
        final int job = player.getJob().getId();

        AssertUtil.isTrue(attack.skill == 0);

        /*
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
        }*/

        if (!player.isAlive()) {
            return;
        }

        AssertUtil.isTrue(attack.skill != ChiefBandit.MESO_EXPLOSION);

        for (Integer oned : attack.allDamage.keySet()) {
            final Monster monster = map.getMonsterByOid(oned);
            if (monster != null) {
                int totDamageToOneMonster = 0;
                List<Integer> onedList = attack.allDamage.get(oned);

                AssertUtil.isTrue(!monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY));
                AssertUtil.isTrue(!monster.isBuffed(MonsterStatus.WEAPON_IMMUNITY));

                AssertUtil.isTrue(!MobId.isDojoBoss(monster.getId()));

                for (Integer eachd : onedList) {
                    if (eachd < 0) {
                        eachd += Integer.MAX_VALUE;
                    }
                    totDamageToOneMonster += eachd;
                }
                monster.aggroMonsterDamage(player, totDamageToOneMonster);

                AssertUtil.isTrue(player.getBuffedValue(EffectType.PICKPOCKET) == null);

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

                AssertUtil.isTrue(!player.isAran());

                AssertUtil.isTrue(player.getBuffedValue(EffectType.HAMSTRING) == null);

                AssertUtil.isTrue(player.getBuffedValue(EffectType.SLOW) == null);

                AssertUtil.isTrue(player.getBuffedValue(EffectType.BLIND) == null);

                AssertUtil.isTrue(job != 121);
                AssertUtil.isTrue(job != 122);

                AssertUtil.isTrue(player.getBuffedValue(EffectType.COMBO_DRAIN) == null);

                AssertUtil.isTrue(job != 412);
                AssertUtil.isTrue(job != 422);
                AssertUtil.isTrue(job != 1411);

                AssertUtil.isTrue(job < 311 || job > 322);

                AssertUtil.isTrue(attack.skill == 0);
                /*
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
                */

                AssertUtil.isTrue(attackEffect == null);

                AssertUtil.isTrue(attack.skill != Paladin.HEAVENS_HAMMER);

                AssertUtil.isTrue(attack.skill != Aran.COMBO_TEMPEST);

                AssertUtil.isTrue(attack.skill != Aran.BODY_PRESSURE);

                map.damageMonster(player.ref(), monster, totDamageToOneMonster);

                AssertUtil.isTrue(!monster.isBuffed(MonsterStatus.WEAPON_REFLECT));
                AssertUtil.isTrue(!monster.isBuffed(MonsterStatus.MAGIC_REFLECT));
            }
        }
    }

    /*
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
    */
}
