package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.EffectType;
import org.gms.client.Family;
import org.gms.client.JobEnum;
import org.gms.client.job.GainStats;
import org.gms.client.job.JobDefinition;
import org.gms.client.job.JobRegistry;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.Stat;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.skills.*;
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
    private JobEnum job = JobEnum.BEGINNER;

    CharacterJob(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    JobEnum getJob() {
        return job;
    }

    void setJob(JobEnum job) {
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
    boolean isA(JobEnum job) {
        return this.job.isA(job);
    }

    /** 职业相等判定（委托 job.equals） */
    boolean equalsJob(JobEnum job) {
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

    JobEnum getJobStyle(byte opt) {
        return JobEnum.getJobStyleInternal(this.getId(), opt);
    }

    JobEnum getJobStyle() {
        return getJobStyle((byte) ((owner.getStr() > owner.getDex()) ? 0x80 : 0x40));
    }

    // ── 转职 ──

    synchronized void changeJob(JobEnum newJob) {
        if (newJob == null) {
            return;//the fuck you doing idiot!
        }

        if (owner.party.canRecvPartySearchInvite && owner.getParty() == null) {
            owner.updatePartySearchAvailability(false);
            this.job = newJob;
            owner.updatePartySearchAvailability(true);
        } else {
            this.job = newJob;
        }

        // 转职一次性授予（HP/MP/AP/SP 全在 advancementGainStats；对称于升级的 gainStats）
        JobDefinition def = JobRegistry.of(newJob);
        GainStats adv = def.advancementGainStats();

        int spGain = adv != null ? adv.sp() : 0;
        if (GameConfig.getServerBoolean("use_enforce_job_sp_range")) {
            spGain = owner.getChangedJobSp(newJob);
        }

        if (spGain > 0) {
            owner.gainSp(spGain, newJob.getId(), true);
        }

        if (adv != null && adv.ap() > 0) {
            owner.gainAp(adv.ap(), true);
        }

        if (!owner.isGM()) {
            for (byte i = 1; i < 5; i++) {
                owner.gainSlots(i, 4, true);
            }
        }

        boolean fixedLevelUpHpMp = true;  // todo: [refactor] hard coded config
        int newMaxHp = 0, newMaxMp = 0;
        if (adv != null) {
            // NEW = applyGrowth(OLD, growth, fixed)，直接取新值应用，不做增量换算
            newMaxHp = CharacterStats.applyGrowth(owner.stats.attrs[StatIndex.MAX_HP], adv.maxHp(), fixedLevelUpHpMp);
            newMaxMp = CharacterStats.applyGrowth(owner.stats.attrs[StatIndex.MAX_MP], adv.maxMp(), fixedLevelUpHpMp);
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

        // effLock 已冗余：applyUpdateSilently/recalcLocalStats 只需 stats.wLock
        owner.stats.wLock.lock();
        try {
            if (adv != null) {
                owner.stats.applyUpdateSilently(new StatsUpdate().setMaxHp(newMaxHp).setMaxMp(newMaxMp));
            }
            owner.recalcLocalStats();

            List<Pair<Stat, Integer>> statup = new ArrayList<>(7);
            statup.add(new Pair<>(Stat.HP, owner.stats.hp));
            statup.add(new Pair<>(Stat.MP, owner.stats.mp));
            statup.add(new Pair<>(Stat.MAXHP, owner.stats.getClientMaxHp()));
            statup.add(new Pair<>(Stat.MAXMP, owner.stats.getClientMaxMp()));
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
        JobDefinition def = JobRegistry.of(JobEnum.getById(jobId));
        for (Integer skillId : def.acquiredSkills()) {
            Skill skill = SkillFactory.getSkill(skillId);
            if (owner.getSkillLevel(skill) > 0) {
                continue;
            }
            owner.changeSkillLevel(skill, (byte) 0, skill.getMasterLevel(), -1);
        }
    }

    // ── 职业定义门面（JobDefinition 具体实现只在 CharacterJob 内部；其他组件只依赖这些简单类型） ──

    int getMaxClassLevel() {
        return JobRegistry.of(job).maxLevel();
    }

    int getMaxLevel() {
        JobDefinition def = JobRegistry.of(job);
        if (!GameConfig.getServerBoolean("use_enforce_job_level_range") || isGmJob()) {
            return def.maxLevel();
        }
        return def.advancementHint();
    }

    /** 指定等级（升级后的新等级）对应的升级奖励；无区间返回 null */
    GainStats gainStatsAtLevel(int level) {
        return JobRegistry.of(job).gainStatsAtLevel(level);
    }
}
