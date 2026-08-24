package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.EffectType;
import org.gms.client.Family;
import org.gms.client.JobEnum;
import org.gms.client.job.AutoAssignApRange;
import org.gms.client.job.GainStats;
import org.gms.client.job.JobDefinition;
import org.gms.client.job.JobRegistry;
import org.gms.client.job.StatRule;
import org.gms.client.job.WeaponRule;
import org.gms.client.weaponType.WeaponTypeDefinition;
import org.gms.client.weaponType.WeaponTypeDefinition.ActionRules;
import org.gms.client.weaponType.WeaponTypeEnum;
import org.gms.client.skill.SkillDefinition;
import org.gms.scripting.ApAssignerScript;
import org.gms.client.skill.SkillRegistry;
import org.gms.client.weaponType.WeaponTypeRegistry;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.PacketStat;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.skills.*;
import org.gms.net.server.world.PartyCharacter;
import org.gms.model.pojo.SkillEntry;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
import org.gms.server.maps.MapleMap;
import org.gms.util.I18nUtil;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** 当前职业（数据驱动定义；JobEnum 仅经 getJob()/setJob()/isA()/equalsJob() 等兼容 API 使用） */
    private JobDefinition job = JobRegistry.of(JobEnum.BEGINNER);

    CharacterJob(Character owner) {
        this.owner = owner;
    }

    // ── 查询（JobEnum 兼容 API） ──

    /** 兼容 API：返回 JobEnum（供 isA/== 等旧式判断）；内部状态是 JobDefinition */
    JobEnum getJob() {
        return JobEnum.getById(job.jobId());
    }

    /** 兼容 API：接收 JobEnum，转存为 JobDefinition */
    void setJob(JobEnum newJob) {
        this.job = JobRegistry.of(newJob);
    }

    int getJobType() {
        return getId() / 1000;
    }

    /** 当前职业 id（数据驱动） */
    int getId() {
        return job.jobId();
    }

    /** 兼容 API：职业归属判定（委托 JobEnum.isA） */
    boolean isA(JobEnum target) {
        return JobEnum.getById(job.jobId()).isA(target);
    }

    /** 兼容 API：职业相等判定 */
    boolean equalsJob(JobEnum target) {
        return job.jobId() == target.getId();
    }

    boolean isGmJob() {
        int jn = JobEnum.getById(job.jobId()).getJobNiche();
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

    /**
     * 当前职业对指定武器的规则：职业覆盖优先（JobDefinition.weaponStatRules，key=武器类型枚举），
     * 无覆盖用武器类型定义默认（data/weapon_type/*.json）。
     * 返回合并后的 WeaponRule：statRule 覆盖优先、actions 覆盖优先（覆盖缺失的维度回退武器默认）。
     */
    WeaponRule getWeaponRule(WeaponTypeEnum type) {
        WeaponTypeDefinition def = WeaponTypeRegistry.byType(type);
        WeaponRule override = job.weaponStatRules().get(type);
        if (override == null) {
            return WeaponRule.of(def.statRule(), def.actions());
        }
        // 覆盖只写维度合并：statRule 覆盖或默认，actions 覆盖或默认
        StatRule statRule = override.statRule() != null ? override.statRule() : def.statRule();
        ActionRules actions = override.actions() != null ? override.actions() : def.actions();
        return WeaponRule.of(statRule, actions);
    }

    // ── 转职 ──

    synchronized void changeJob(JobEnum newJob) {
        if (newJob == null) {
            return;//the fuck you doing idiot!
        }

        // 兼容 API：接收 JobEnum，转存为 JobDefinition
        JobDefinition newDef = JobRegistry.of(newJob);

        if (owner.party.canRecvPartySearchInvite && owner.getParty() == null) {
            owner.updatePartySearchAvailability(false);
            this.job = newDef;
            owner.updatePartySearchAvailability(true);
        } else {
            this.job = newDef;
        }

        // 转职一次性授予（HP/MP/AP/SP 全在 advancementGainStats；对称于升级的 gainStats）
        JobDefinition def = newDef;
        GainStats adv = def.advancementGainStats();

        int spGain = adv != null ? adv.sp() : 0;
        if (GameConfig.getServerBoolean("use_enforce_job_sp_range")) {
            spGain = owner.getChangedJobSp(newJob);
        }

        if (spGain > 0) {
            owner.gainSp(spGain, def.jobId(), true);
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
        // NEW = applyGrowth(OLD, growth, fixed)（有加有乘），直接经 Growth 事务取新值应用


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

        // effLock 已冗余：commitSilently/recalc 只需 stats.wLock
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            if (adv != null) {
                owner.stats.update()
                        .add(Stat.MAX_HP, rollGrowthGain(adv.maxHp(), fixedLevelUpHpMp))
                        .multiply(Stat.MAX_HP, adv.maxHp().multiply())
                        .add(Stat.MAX_MP, rollGrowthGain(adv.maxMp(), fixedLevelUpHpMp))
                        .multiply(Stat.MAX_MP, adv.maxMp().multiply())
                        .commitSilently();
            }
            owner.recalc();

            List<Pair<PacketStat, Integer>> statup = new ArrayList<>(7);
            statup.add(new Pair<>(PacketStat.HP, owner.stats.getHp()));
            statup.add(new Pair<>(PacketStat.MP, owner.stats.getMp()));
            statup.add(new Pair<>(PacketStat.MAXHP, owner.stats.getClientMaxHp()));
            statup.add(new Pair<>(PacketStat.MAXMP, owner.stats.getClientMaxMp()));
            statup.add(new Pair<>(PacketStat.AVAILABLEAP, owner.stats.getRemainingAp()));
            statup.add(new Pair<>(PacketStat.AVAILABLESP, owner.sp.remainingSp[CharacterSp.indexOf(getId())]));
            statup.add(new Pair<>(PacketStat.JOB, getId()));
            owner.sendPacket(PacketCreator.updatePlayerStats(statup, true, owner));
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
        JobDefinition def = JobRegistry.of(jobId);
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
        return job.maxLevel();
    }

    int getMaxLevel() {
        if (!GameConfig.getServerBoolean("use_enforce_job_level_range") || isGmJob()) {
            return job.maxLevel();
        }
        return job.advancementHint();
    }

    /** 指定等级（升级后的新等级）对应的升级奖励；无区间返回 null */
    GainStats gainStatsAtLevel(int level) {
        return job.gainStatsAtLevel(level);
    }

    // ── 升级职业授予（levelUp 内所有职业强相关逻辑） ──

    /**
     * 升级授予（CharacterLevel.levelUp 调用，oldLevel = 升级前等级）：
     * 按新等级查 JobDefinition levelUp 区间 → 新手自动分配 / maxHp·maxMp·AP·SP 授予 → 技能加成 → INT 加成。
     */
    void applyLevelUpRewards(int oldLevel) {
        boolean fixed = true;  // todo: [refactor] hard coded config

        // 奖励按升级后的新等级（oldLevel+1）查——"N 级才能获得的属性"到达 N 级才生效
        GainStats gs = gainStatsAtLevel(oldLevel + 1);
        if (gs != null) {
            owner.stats.update()
                    .add(Stat.MAX_HP, rollGrowthGain(gs.maxHp(), fixed))
                    .multiply(Stat.MAX_HP, gs.maxHp().multiply())
                    .add(Stat.MAX_MP, rollGrowthGain(gs.maxMp(), fixed))
                    .multiply(Stat.MAX_MP, gs.maxMp().multiply())
                    .commitSilently();
            if (gs.ap() > 0) {
                owner.gainAp(gs.ap(), true);
            }
            if (gs.sp() > 0) {
                owner.gainSp(gs.sp(), job.jobId(), true);
            }
        }

        // 自动分配 AP：命中 autoAssignAp 区间（按升级前等级 oldLevel 判定）时，
        // 按 apAutoAssignKey 对应脚本把当前剩余 AP 分配掉（分配方式由脚本决定；
        // 脚本按升级后等级 oldLevel+1 计算属性需求——与升级奖励按新等级查一致）
        if (matchesAutoAssignAp(oldLevel)) {
            autoAssignApByScript(oldLevel + 1);
        }

        // 技能被动加成（Improving MaxHP/MaxMP）：遍历已学技能查 SkillDefinition 的
        // increaseMaxHpOnLevelUp / increaseMaxMpOnLevelUp（wz effect 字段名），
        // 不再按职业特判挑技能——学到对应被动即生效。INT 加成（非 JobDefinition 数据）
        StatUpdateBuilder growthChanges = owner.stats.update();
        for (Map.Entry<Skill, SkillEntry> e : owner.getSkills().entrySet()) {
            SkillDefinition def = SkillRegistry.of(e.getKey().getId());
            if (def == null || def.passive() == null) {
                continue;
            }
            int level = e.getValue().skillLevel;
            if (level <= 0) {
                continue;
            }
            BuffEffectData effect = e.getKey().getEffect(level);
            SkillDefinition.Passive passive = def.passive();
            if (passive.increaseMaxHpOnLevelUp() != null) {
                growthChanges.add(Stat.MAX_HP, effect.getValue(passive.increaseMaxHpOnLevelUp()));
            }
            if (passive.increaseMaxMpOnLevelUp() != null) {
                growthChanges.add(Stat.MAX_MP, effect.getValue(passive.increaseMaxMpOnLevelUp()));
            }
        }

        if (GameConfig.getServerBoolean("use_randomize_hpmp_gain")) {
            // 升级按智力授予 MP：除数由职业定义决定（MAGICIAN 系 20，其他 10）
            int intBonus = owner.stats.getTotal(Stat.INT) / job.mpIntDivisor();
            growthChanges.add(Stat.MAX_MP, intBonus);
        }
        growthChanges.commitSilently();
    }

    // ── 成长工具（升级/转职"有加有乘"的加数部分；乘法由 Change.Multiply 在事务内完成） ──

    /** 成长加成量：fixed = 区间平均值，否则随机；NEW = (OLD + gain) * multiply 中的 gain */
    static int rollGrowthGain(GainStats.Growth growth, boolean fixed) {
        return fixed ? (growth.addMin() + growth.addMax()) / 2 : Randomizer.rand(growth.addMin(), growth.addMax());
    }

    // ── 自动分配 AP（等级区间 + apAutoAssignKey 脚本） ──

    /** 升级前等级 oldLevel 是否命中本职业 autoAssignAp 区间 */
    private boolean matchesAutoAssignAp(int oldLevel) {
        for (AutoAssignApRange range : job.autoAssignAp()) {
            if (range.matches(oldLevel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 apAutoAssignKey 调用对应脚本把当前剩余 AP 分配掉。
     * 升级场景无装备加成需求，装备相关参数传空/0。
     *
     * @param newLevel 升级后等级（脚本按此计算属性需求；升级自动分配传 oldLevel+1）
     */
    private void autoAssignApByScript(int newLevel) {
        int[] gain = ApAssignerScript.assign(owner, job.apAutoAssignKey(), newLevel,
                owner.getRemainingAp(), List.of(), List.of(), List.of(), 0, 0, 0);
        if (gain[0] + gain[1] + gain[2] + gain[3] > 0) {
            owner.assignStrDexIntLuk(gain[0], gain[1], gain[2], gain[3]);
        }
    }
}
