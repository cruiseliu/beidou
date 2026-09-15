package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.Skill;
import org.gms.net.server.Server;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.Collections;
import java.util.List;

import org.gms.client.SkillFactory;
import org.gms.constants.skills.Corsair;
import org.gms.constants.skills.Crusader;
import org.gms.constants.skills.DawnWarrior;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.ThunderBreaker;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 特殊技能模块组件：战船（battleship HP/冷却）+ 能量充能（handleEnergyChargeGain）+ 斗气珠（handleOrbconsume）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（handleEnergyChargeGain/handleOrbconsume/... 对外转发）。
 *
 * 边界：只承载特殊技能语义——战船血量、能量条（energyBar）、斗气珠消耗。
 * 依赖经 owner 门面调用（sendPacket/getMap/getSkillLevel/setBuffedValue/...）。
 */
class CharacterSpecialSkills {
    private final Character owner;

    /** 能量条（能量充能进度） */
    private int energyBar;

    /** 战船当前血量 */
    int battleshipHp = 0;

    CharacterSpecialSkills(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    int getEnergyBar() {
        return energyBar;
    }

    int getBattleshipHp() {
        return battleshipHp;
    }

    void setEnergyBar(int energyBar) {
        this.energyBar = energyBar;
    }

    boolean isRidingBattleship() {
        Integer bv = owner.getBuffedValue(EffectType.MONSTER_RIDING);
        return bv != null && bv.equals(Corsair.BATTLE_SHIP);
    }

    // ── 战船 ──

    void announceBattleshipHp() {
        owner.sendPacket(PacketCreator.skillCooldown(5221999, battleshipHp));
    }

    void decreaseBattleshipHp(int decrease) {
        this.battleshipHp -= decrease;
        if (battleshipHp <= 0) {
            Skill battleship = SkillFactory.getSkill(Corsair.BATTLE_SHIP);
            int cooldown = battleship.getEffect(owner.getSkillLevel(Corsair.BATTLE_SHIP)).getCooldown();
            owner.sendPacket(PacketCreator.skillCooldown(Corsair.BATTLE_SHIP, cooldown));
            owner.addCooldown(Corsair.BATTLE_SHIP, Server.getInstance().getCurrentTime(), SECONDS.toMillis(cooldown));
            owner.removeCooldown(5221999);
            owner.cancelEffectFromBuffStat(EffectType.MONSTER_RIDING);
        } else {
            announceBattleshipHp();
            owner.addCooldown(5221999, 0, Long.MAX_VALUE);
        }
    }

    void resetBattleshipHp() {
        int bshipLevel = Math.max(owner.getLevel() - 120, 0);  // thanks alex12 for noticing battleship HP issues for low-level players
        this.battleshipHp = 400 * owner.getSkillLevel(Corsair.BATTLE_SHIP) + (bshipLevel * 200);
    }

    // ── 能量充能 ──

    void handleEnergyChargeGain() { // to get here energychargelevel has to be > 0
        Skill energycharge = owner.isCygnus() ? SkillFactory.getSkill(ThunderBreaker.ENERGY_CHARGE) : SkillFactory.getSkill(Marauder.ENERGY_CHARGE);
        BuffEffectData ceffect;
        ceffect = energycharge.getEffect(owner.getSkillLevel(energycharge.getId()));
        TimerManager tMan = TimerManager.getInstance();
        if (energyBar < 10000) {
            energyBar += 102;
            if (energyBar > 10000) {
                energyBar = 10000;
            }
            List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.ENERGY_CHARGE, energyBar));
            owner.setBuffedValue(EffectType.ENERGY_CHARGE, energyBar);
            owner.sendPacket(PacketCreator.giveBuff(energyBar, 0, stat));
            owner.sendPacket(PacketCreator.showOwnBuffEffect(energycharge.getId(), 2));
            owner.getMapRef().broadcastPacket(owner.ref(), PacketCreator.showBuffEffect(owner.getId(), energycharge.getId(), 2));
            owner.getMapRef().broadcastPacket(owner.ref(), PacketCreator.giveForeignPirateBuff(owner.getId(), energycharge.getId(),
                    ceffect.getDuration(), stat));
        }
        if (energyBar >= 10000 && energyBar < 11000) {
            energyBar = 15000;
            final Character chr = owner;
            tMan.schedule(() -> {
                energyBar = 0;
                List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.ENERGY_CHARGE, energyBar));
                owner.setBuffedValue(EffectType.ENERGY_CHARGE, energyBar);
                owner.sendPacket(PacketCreator.giveBuff(energyBar, 0, stat));
                owner.getMapRef().broadcastPacket(chr.ref(), PacketCreator.cancelForeignFirstDebuff(owner.getId(), ((long) 1) << 50));
            }, ceffect.getDuration());
        }
    }

    // ── 斗气珠 ──

    void handleOrbconsume() {
        int skillid = owner.isCygnus() ? DawnWarrior.COMBO : Crusader.COMBO;
        Skill combo = SkillFactory.getSkill(skillid);
        List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.COMBO, 1));
        owner.setBuffedValue(EffectType.COMBO, 1);
        owner.sendPacket(PacketCreator.giveBuff(skillid, combo.getEffect(owner.getSkillLevel(skillid)).getDuration() + (int) ((owner.getBuffedStarttime(EffectType.COMBO) - System.currentTimeMillis())), stat));
        owner.getMapRef().broadcastMessage(owner.ref(), PacketCreator.giveForeignBuff(owner.getId(), stat), false);
    }
}
