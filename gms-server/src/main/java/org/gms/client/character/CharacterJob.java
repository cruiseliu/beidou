package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.EffectType;
import org.gms.client.Family;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.Stat;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.skills.*;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.world.PartyCharacter;
import org.gms.server.TimerManager;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * 职业模块组件：当前职业 + 转职流程（changeJob）+ 职业查询门面 + 转职专属的 mastery 授予。
 * 仿照 CharacterBuffs/CharacterPets 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面。
 *
 * 边界：只承载语义上属于职业管理的逻辑——job 状态、职业查询、转职（changeJob）、
 * 转职专属的 setMasteries。转职/升级共用的 SP 计算（getSpGain/getJobMaxSp 等）与
 * gainAp/gainSp/gainSlots 等通用加点留在各自模块，不属于本组件。
 */
class CharacterJob {
    private final Character owner;

    /** 当前职业 */
    private Job job = Job.BEGINNER;

    CharacterJob(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    Job getJob() {
        return job;
    }

    void setJob(Job job) {
        this.job = job;
    }

    int getJobType() {
        return getId() / 1000;
    }

    /** 当前职业 id（委托 job.getId()） */
    int getId() {
        return job.getId();
    }

    /** 职业归属判定（委托 job.isA） */
    boolean isA(Job job) {
        return this.job.isA(job);
    }

    /** 职业相等判定（委托 job.equals） */
    boolean equalsJob(Job job) {
        return this.job.equals(job);
    }

    boolean isGmJob() {
        int jn = job.getJobNiche();
        return jn >= 8 && jn <= 9;
    }

    boolean isCygnus() {
        return getJobType() == 1;
    }

    boolean isAran() {
        return getId() >= 2000 && getId() <= 2112;
    }

    boolean isBeginnerJob() {
        return (getId() == 0 || getId() == 1000 || getId() == 2000);
    }

    Job getJobStyle(byte opt) {
        return Job.getJobStyleInternal(this.getId(), opt);
    }

    Job getJobStyle() {
        return getJobStyle((byte) ((owner.getStr() > owner.getDex()) ? 0x80 : 0x40));
    }

    // ── 转职 ──

    synchronized void changeJob(Job newJob) {
        if (newJob == null) {
            return;//the fuck you doing idiot!
        }

        if (owner.canRecvPartySearchInvite && owner.getParty() == null) {
            owner.updatePartySearchAvailability(false);
            this.job = newJob;
            owner.updatePartySearchAvailability(true);
        } else {
            this.job = newJob;
        }

        int spGain = 1;
        if (GameConstants.hasSPTable(newJob)) {
            spGain += 2;
        } else {
            if (newJob.getId() % 10 == 2) {
                spGain += 2;
            }

            if (GameConfig.getServerBoolean("use_enforce_job_sp_range")) {
                spGain = owner.getChangedJobSp(newJob);
            }
        }

        if (spGain > 0) {
            owner.gainSp(spGain, newJob.getId(), true);
        }

        // thanks xinyifly for finding out missing AP awards (AP Reset can be used as a compass)
        if (newJob.getId() % 100 >= 1) {
            if (this.isCygnus()) {
                owner.gainAp(7, true);
            } else {
                if (GameConfig.getServerBoolean("use_starting_ap_4") || newJob.getId() % 10 >= 1) {
                    owner.gainAp(5, true);
                }
            }
        } else {    // thanks Periwinks for noticing an AP shortage from lower levels
            if (GameConfig.getServerBoolean("use_starting_ap_4") && newJob.getId() % 1000 >= 1) {
                owner.gainAp(4, true);
            }
        }

        if (!owner.isGM()) {
            for (byte i = 1; i < 5; i++) {
                owner.gainSlots(i, 4, true);
            }
        }

        boolean fixedLevelUpHpMp = true;  // todo: [refactor] hard coded config
        int addhp = 0, addmp = 0;
        int job_ = getId() % 1000; // lame temp "fix"
        if (job_ == 100) {                      // 1st warrior
            addhp += CharacterStats.getHpMpGainFromRange(200, 250, fixedLevelUpHpMp);
        } else if (job_ == 200) {               // 1st mage
            addmp += CharacterStats.getHpMpGainFromRange(100, 150, fixedLevelUpHpMp);
        } else if (job_ % 100 == 0) {           // 1st others
            addhp += CharacterStats.getHpMpGainFromRange(100, 150, fixedLevelUpHpMp);
            addmp += CharacterStats.getHpMpGainFromRange(25, 50, fixedLevelUpHpMp);
        } else if (job_ > 0 && job_ < 200) {    // 2nd~4th warrior
            addhp += CharacterStats.getHpMpGainFromRange(300, 350, fixedLevelUpHpMp);
        } else if (job_ < 300) {                // 2nd~4th mage
            addmp += CharacterStats.getHpMpGainFromRange(450, 500, fixedLevelUpHpMp);
        } else {                  // 2nd~4th others
            addhp += CharacterStats.getHpMpGainFromRange(300, 350, fixedLevelUpHpMp);
            addmp += CharacterStats.getHpMpGainFromRange(150, 200, fixedLevelUpHpMp);
        }

        /*
        //aran perks?
        int newJobId = newJob.getId();
        if(newJobId == 2100) {          // become aran1
            addhp += 275;
            addmp += 15;
        } else if(newJobId == 2110) {   // become aran2
            addmp += 275;
        } else if(newJobId == 2111) {   // become aran3
            addhp += 275;
            addmp += 275;
        }
        */

        // effLock 已冗余：addMaxMPMaxHP/recalcLocalStats 只需 stats.wLock
        owner.stats.wLock.lock();
        try {
            owner.stats.addMaxMPMaxHP(addhp, addmp, true);
            owner.recalcLocalStats();

            List<Pair<Stat, Integer>> statup = new ArrayList<>(7);
            statup.add(new Pair<>(Stat.HP, owner.stats.hp));
            statup.add(new Pair<>(Stat.MP, owner.stats.mp));
            statup.add(new Pair<>(Stat.MAXHP, owner.stats.clientMaxHp));
            statup.add(new Pair<>(Stat.MAXMP, owner.stats.clientMaxMp));
            statup.add(new Pair<>(Stat.AVAILABLEAP, owner.ap.remainingAp));
            statup.add(new Pair<>(Stat.AVAILABLESP, owner.sp.remainingSp[CharacterSp.indexOf(getId())]));
            statup.add(new Pair<>(Stat.JOB, getId()));
            owner.sendPacket(PacketCreator.updatePlayerStats(statup, true, owner));
        } finally {
            owner.stats.wLock.unlock();

        }

        owner.setMPC(new PartyCharacter(owner));
        owner.silentPartyUpdate();

        if (owner.getDragon() != null) {
            owner.getMap().broadcastMessage(PacketCreator.removeDragon(owner.getDragon().getObjectId()));
            owner.setDragon(null);
        }

        if (owner.getGuildId() > 0) {
            owner.getGuild().broadcast(PacketCreator.jobMessage(0, getId(), owner.getName()), owner.getId());
        }
        Family family = owner.getFamily();
        if (family != null) {
            family.broadcast(PacketCreator.jobMessage(1, getId(), owner.getName()), owner.getId());
        }
        setMasteries(this.getId());
        owner.guild.guildUpdate();

        broadcastChangeJob();

        if (GameConstants.hasSPTable(newJob) && newJob.getId() != 2001) {
            if (owner.getBuffedValue(EffectType.MONSTER_RIDING) != null) {
                owner.cancelBuffStats(EffectType.MONSTER_RIDING);
            }
            owner.createDragon();
        }

        if (GameConfig.getServerBoolean("use_announce_change_job")) {
            if (!owner.isGM()) {
                owner.broadcastAcquaintances(6, I18nUtil.getMessage("Character.Job.Change.message", owner.getName(), GameConstants.ordinal(GameConstants.getJobBranch(newJob)), GameConstants.getJobName(this.getId())));        // thanks Vcoc for noticing job name appearing in uppercase here
            }
        }
    }

    private void broadcastChangeJob() {
        for (Character chr : owner.getMap().getAllPlayers()) {
            Client chrC = chr.getClient();

            if (chrC != null) {     // propagate new job 3rd-person effects (FJ, Aran 1st strike, etc)
                owner.sendDestroyData(chrC);
                owner.sendSpawnData(chrC);
            }
        }

        // need to delay to ensure clientside has finished reloading character data     //需要延迟以确保客户端已完成重新加载角色数据
        TimerManager.getInstance().schedule(() -> {
            MapleMap map = owner.getMap();

            if (map != null) {
                map.broadcastMessage(owner, PacketCreator.showForeignEffect(owner.getId(), 8), false);
            }
        }, 777);
    }

    // ── 转职专属：mastery 授予 ──

    void setMasteries(int jobId) {
        int[] skills = new int[]{0, 0, 0, 0};
        if (jobId == 112) {
            skills[0] = Hero.ACHILLES;
            skills[1] = Hero.MONSTER_MAGNET;
            skills[2] = Hero.BRANDISH;
        } else if (jobId == 122) {
            skills[0] = Paladin.ACHILLES;
            skills[1] = Paladin.MONSTER_MAGNET;
            skills[2] = Paladin.BLAST;
        } else if (jobId == 132) {
            skills[0] = DarkKnight.BEHOLDER;
            skills[1] = DarkKnight.ACHILLES;
            skills[2] = DarkKnight.MONSTER_MAGNET;
        } else if (jobId == 212) {
            skills[0] = FPArchMage.BIG_BANG;
            skills[1] = FPArchMage.MANA_REFLECTION;
            skills[2] = FPArchMage.PARALYZE;
        } else if (jobId == 222) {
            skills[0] = ILArchMage.BIG_BANG;
            skills[1] = ILArchMage.MANA_REFLECTION;
            skills[2] = ILArchMage.CHAIN_LIGHTNING;
        } else if (jobId == 232) {
            skills[0] = Bishop.BIG_BANG;
            skills[1] = Bishop.MANA_REFLECTION;
            skills[2] = Bishop.HOLY_SHIELD;
        } else if (jobId == 312) {
            skills[0] = Bowmaster.BOW_EXPERT;
            skills[1] = Bowmaster.HAMSTRING;
            skills[2] = Bowmaster.SHARP_EYES;
        } else if (jobId == 322) {
            skills[0] = Marksman.MARKSMAN_BOOST;
            skills[1] = Marksman.BLIND;
            skills[2] = Marksman.SHARP_EYES;
        } else if (jobId == 412) {
            skills[0] = NightLord.SHADOW_STARS;
            skills[1] = NightLord.SHADOW_SHIFTER;
            skills[2] = NightLord.VENOMOUS_STAR;
        } else if (jobId == 422) {
            skills[0] = Shadower.SHADOW_SHIFTER;
            skills[1] = Shadower.VENOMOUS_STAB;
            skills[2] = Shadower.BOOMERANG_STEP;
        } else if (jobId == 512) {
            skills[0] = Buccaneer.BARRAGE;
            skills[1] = Buccaneer.ENERGY_ORB;
            skills[2] = Buccaneer.SPEED_INFUSION;
            skills[3] = Buccaneer.DRAGON_STRIKE;
        } else if (jobId == 522) {
            skills[0] = Corsair.ELEMENTAL_BOOST;
            skills[1] = Corsair.BULLSEYE;
            skills[2] = Corsair.WRATH_OF_THE_OCTOPI;
            skills[3] = Corsair.RAPID_FIRE;
        } else if (jobId == 2112) {
            skills[0] = Aran.OVER_SWING;
            skills[1] = Aran.HIGH_MASTERY;
            skills[2] = Aran.FREEZE_STANDING;
        } else if (jobId == 2217) {
            skills[0] = Evan.MAPLE_WARRIOR;
            skills[1] = Evan.ILLUSION;
        } else if (jobId == 2218) {
            skills[0] = Evan.BLESSING_OF_THE_ONYX;
            skills[1] = Evan.BLAZE;
        }
        for (Integer skillId : skills) {
            if (skillId != 0) {
                Skill skill = SkillFactory.getSkill(skillId);
                final int skilllevel = owner.getSkillLevel(skill);
                if (skilllevel > 0) {
                    continue;
                }

                owner.changeSkillLevel(skill, (byte) 0, 10, -1);
            }
        }
    }
}
