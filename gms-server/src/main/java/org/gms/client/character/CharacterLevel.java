package org.gms.client.character;

import org.gms.client.Disease;
import org.gms.client.FamilyEntry;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.PacketStat;
import org.gms.client.job.GainStats;

import static org.gms.client.character.Stat.INT;
import static org.gms.client.character.Stat.STR;
import static org.gms.client.character.Stat.DEX;
import static org.gms.client.character.Stat.LUK;
import org.gms.config.GameConfig;
import org.gms.constants.game.ExpTable;
import org.gms.util.Pair;
import org.gms.constants.game.GameConstants;
import org.gms.constants.net.ServerConstants;
import org.gms.constants.id.ItemId;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.*;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.server.ThreadManager;
import org.gms.server.ExpLogger;
import org.gms.server.ExpLogger.ExpLogRecord;
import org.gms.server.life.PlayerNPC;
import org.gms.net.server.world.PartyCharacter;
import org.gms.util.I18nUtil;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 经验/等级模块组件：经验（exp/gachaExp/totalExpGained）+ 等级（level）+ 升级流程（gainExp/levelUp）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（gainExp/getLevel/levelUp/... 对外转发）。
 *
 * 边界：只承载经验/等级语义——经验获取/扣除、经验加成、升级与升级奖励编排。
 * 依赖经 owner 门面调用（updateSingleStat/sendPacket/getMap/...）与组件访问（stats/ap/sp/job/guild/family/...）。
 */
class CharacterLevel {
    private static final Logger log = LoggerFactory.getLogger(CharacterLevel.class);

    private final Character owner;

    private int level;
    private final AtomicInteger exp = new AtomicInteger();
    private final AtomicInteger gachaExp = new AtomicInteger();
    private long totalExpGained = 0;

    CharacterLevel(Character owner) {
        this.owner = owner;
        this.level = 1;
    }

    // ── 经验获取 ──

    public void gainGachaExp() {
        int expgain = 0;
        long currentgexp = gachaExp.get();

        int levelUpNeed = ExpTable.getExpNeededForLevel(level) - exp.get();
        if (currentgexp >= levelUpNeed) {
            expgain += Math.max(0, levelUpNeed);

            int nextneed = ExpTable.getExpNeededForLevel(level + 1);
            if (currentgexp - expgain >= nextneed) {
                expgain += nextneed;
            }

            gachaExp.set((int) (currentgexp - expgain));
        } else {
            expgain = gachaExp.getAndSet(0);
        }
        gainExp(expgain, false, true);
        owner.updateSingleStat(PacketStat.GACHAEXP, gachaExp.get());
    }

    public void addGachaExp(int gain) {
        owner.updateSingleStat(PacketStat.GACHAEXP, gachaExp.addAndGet(gain));
    }

    public void gainExp(int gain) {
        gainExp(gain, true, true);
    }

    public void gainExp(int gain, boolean show, boolean inChat) {
        gainExp(gain, show, inChat, true);
    }

    public void gainExp(int gain, boolean show, boolean inChat, boolean white) {
        gainExp(gain, 0, show, inChat, white);
    }

    public void gainExp(int gain, int party, boolean show, boolean inChat, boolean white) {
        if (owner.hasDisease(Disease.CURSE)) {
            gain *= 0.5;
            party *= 0.5;
        }

        if (gain < 0) {
            gain = Integer.MAX_VALUE;   // integer overflow, heh.
        }

        if (party < 0) {
            party = Integer.MAX_VALUE;  // integer overflow, heh.
        }

        int equip = (int) Math.min((long) (gain / 10) * owner.pendantExp, Integer.MAX_VALUE);

        gainExpInternal(gain, equip, party, show, inChat, white);
    }

    public void loseExp(int loss, boolean show, boolean inChat) {
        loseExp(loss, show, inChat, true);
    }

    public void loseExp(int loss, boolean show, boolean inChat, boolean white) {
        gainExpInternal(-loss, 0, 0, show, inChat, white);
    }

    private synchronized void gainExpInternal(long gain, int equip, int party, boolean show, boolean inChat, boolean white) {   // need of method synchonization here detected thanks to MedicOP
        long total = Math.max(gain + equip + party, -exp.get());

        if (level < owner.getMaxLevel() && (owner.allowExpGain || owner.getEventInstance() != null)) {
            long leftover = 0;
            long nextExp = exp.get() + total;

            if (nextExp > (long) Integer.MAX_VALUE) {
                total = Integer.MAX_VALUE - exp.get();
                leftover = nextExp - Integer.MAX_VALUE;
            }
            owner.updateSingleStat(PacketStat.EXP, exp.addAndGet((int) total));
            totalExpGained += total;
            if (show) {
                announceExpGain(gain, equip, party, inChat, white);
            }
            while (exp.get() >= ExpTable.getExpNeededForLevel(level)) {
                levelUp(true);

                String msg = I18nUtil.getMessage("Character.levelUp.globalNotice", owner.getName(), owner.getMap().getMapName(), getLevel());
                if (GameConfig.getServerBoolean("use_announce_global_level_up") && !owner.isGM()) {
                    for (Character player : owner.getWorldServer().getPlayerStorage().getAllCharacters()) {
                        // 如果玩家在商城，将会以弹窗的形式发送，一堆弹窗会把玩家逼疯！
                        if (player.getCashShop().isOpened()) {
                            continue;
                        }
                        player.dropMessage(6, msg);
                    }
                    log.info(msg);
                }
                if (level == owner.getMaxLevel()) {
                    setExp(0);
                    owner.updateSingleStat(PacketStat.EXP, 0);
                    break;
                }
                if (GameConfig.getServerBoolean("use_level_up_protect")) break;
            }

            if (leftover > 0) {
                gainExpInternal(leftover, equip, party, false, inChat, white);
            } else {
                owner.lastExpGainTime = System.currentTimeMillis();

                if (GameConfig.getServerBoolean("use_exp_gain_log")) {
                    ExpLogRecord expLogRecord = new ExpLogger.ExpLogRecord(
                            owner.getWorldServer().getExpRate(),
                            owner.getCouponExpRate(),
                            totalExpGained,
                            exp.get(),
                            new Timestamp(owner.lastExpGainTime),
                            owner.getId()
                    );
                    ExpLogger.putExpLogRecord(expLogRecord);
                }

                totalExpGained = 0;
            }
        }
    }

    private void announceExpGain(long gain, int equip, int party, boolean inChat, boolean white) {
        gain = Math.min(gain, Integer.MAX_VALUE);
        if (gain == 0) {
            if (party == 0) {
                return;
            }

            gain = party;
            party = 0;
            white = false;
        }

        owner.sendPacket(PacketCreator.getShowExpGain((int) gain, equip, party, inChat, white));
    }

    public int getExp() {
        return exp.get();
    }

    public int getGachaExp() {
        return gachaExp.get();
    }

    private void levelUpGainSp() {
        // 升级 SP 完全由注册表 levelUp 区间驱动（新手 2~7 级 sp=1 等显式数据；不再隐式特判/推算）
        GainStats gs = owner.job.gainStatsAtLevel(level);
        if (gs == null) {
            return;
        }
        int spGain = gs.sp();
        if (spGain > 0) {
            owner.gainSp(spGain, owner.job.getId(), true);
        }
    }

    public synchronized void levelUp(boolean takeexp) {
        Skill improvingMaxHP = null;
        Skill improvingMaxMP = null;
        int improvingMaxHPLevel = 0;
        int improvingMaxMPLevel = 0;

        // 奖励按升级后的新等级（level+1）查——"N 级才能获得的属性"到达 N 级才生效，1 级角色没有 2 级的属性
        GainStats gs = owner.job.gainStatsAtLevel(level + 1);

        boolean isBeginner = owner.isBeginnerJob();
        if (GameConfig.getServerBoolean("use_auto_assign_starters_ap") && isBeginner && level < 11) {
        // effLock 已冗余：gainAp/assignStrDexIntLuk 只需 owner.stats.wLock
            try (var ignored = Locks.acquire(owner.stats.wLock)) {
                owner.gainAp(5, true);

                int str = 0, dex = 0;
                if (level < 6) {
                    str += 5;
                } else {
                    str += 4;
                    dex += 1;
                }

                owner.assignStrDexIntLuk(str, dex, 0, 0);
            }
        } else {
            // 每级 AP 直接取注册表 levelUp 区间的最终值（不叠加基准）
            owner.gainAp(gs != null ? gs.ap() : 0, true);
        }

        boolean fixedLevelUpHpMp = true;  // todo: [refactor] hard coded config
        // 有加有乘成长：add(随机加成) + multiply(系数)，事务内读旧 maxHp 计算并触发 recalc
        StatUpdateBuilder growthChanges = owner.stats.update();
        if (gs != null) {
            growthChanges.add(Stat.MAX_HP, CharacterJob.rollGrowthGain(gs.maxHp(), fixedLevelUpHpMp))
                    .multiply(Stat.MAX_HP, gs.maxHp().multiply())
                    .add(Stat.MAX_MP, CharacterJob.rollGrowthGain(gs.maxMp(), fixedLevelUpHpMp))
                    .multiply(Stat.MAX_MP, gs.maxMp().multiply());
        }
        // 技能加成（Improving MaxHP/MaxMP）仍按原逻辑计算
        if (owner.job.isA(JobEnum.WARRIOR) || owner.job.isA(JobEnum.DAWNWARRIOR1)) {
            improvingMaxHP = owner.isCygnus() ? SkillFactory.getSkill(DawnWarrior.MAX_HP_INCREASE) : SkillFactory.getSkill(Warrior.IMPROVED_MAXHP);
            if (owner.job.isA(JobEnum.CRUSADER)) {
                improvingMaxMP = SkillFactory.getSkill(1210000);
            } else if (owner.job.isA(JobEnum.DAWNWARRIOR2)) {
                improvingMaxMP = SkillFactory.getSkill(11110000);
            }
            improvingMaxHPLevel = owner.getSkillLevel(improvingMaxHP);
        } else if (owner.job.isA(JobEnum.MAGICIAN) || owner.job.isA(JobEnum.BLAZEWIZARD1)) {
            improvingMaxMP = owner.isCygnus() ? SkillFactory.getSkill(BlazeWizard.INCREASING_MAX_MP) : SkillFactory.getSkill(Magician.IMPROVED_MAX_MP_INCREASE);
            improvingMaxMPLevel = owner.getSkillLevel(improvingMaxMP);
        } else if (owner.job.isA(JobEnum.PIRATE) || owner.job.isA(JobEnum.THUNDERBREAKER1)) {
            improvingMaxHP = owner.isCygnus() ? SkillFactory.getSkill(ThunderBreaker.IMPROVE_MAX_HP) : SkillFactory.getSkill(Brawler.IMPROVE_MAX_HP);
            improvingMaxHPLevel = owner.getSkillLevel(improvingMaxHP);
        }
        if (improvingMaxHPLevel > 0 && (owner.job.isA(JobEnum.WARRIOR) || owner.job.isA(JobEnum.PIRATE) || owner.job.isA(JobEnum.DAWNWARRIOR1) || owner.job.isA(JobEnum.THUNDERBREAKER1))) {
            growthChanges.add(Stat.MAX_HP, improvingMaxHP.getEffect(improvingMaxHPLevel).getX());
        }
        if (improvingMaxMPLevel > 0 && (owner.job.isA(JobEnum.MAGICIAN) || owner.job.isA(JobEnum.CRUSADER) || owner.job.isA(JobEnum.BLAZEWIZARD1))) {
            growthChanges.add(Stat.MAX_MP, improvingMaxMP.getEffect(improvingMaxMPLevel).getX());
        }

        if (GameConfig.getServerBoolean("use_randomize_hpmp_gain")) {
            int intBonus = owner.getJobStyle() == JobEnum.MAGICIAN
                    ? owner.stats.getTotal(INT) / 20
                    : owner.stats.getTotal(INT) / 10;
            growthChanges.add(Stat.MAX_MP, intBonus);
        }

        growthChanges.commitSilently();

        if (takeexp) {
            exp.addAndGet(-ExpTable.getExpNeededForLevel(level));
            if (exp.get() < 0) {
                exp.set(0);
            }
        }

        level++;
        if (level >= owner.getMaxClassLevel()) {
            exp.set(0);

            int maxClassLevel = owner.getMaxClassLevel();
            if (level == maxClassLevel) {
                if (!owner.isGM()) {
                    if (GameConfig.getServerBoolean("playernpc_auto_deploy")) {
                        ThreadManager.getInstance().newTask(() -> PlayerNPC.spawnPlayerNPC(GameConstants.getHallOfFameMapid(owner.getJob()), owner));
                    }

                    final String names = (owner.getMedalText() + owner.getName());
                    owner.getWorldServer().broadcastPacket(PacketCreator.serverNotice(6, String.format(ServerConstants.LEVEL_200, names, maxClassLevel, names)));
                }
            }

            level = maxClassLevel; //To prevent levels past the maximum
        }

        levelUpGainSp();

        // effLock 已冗余：recalc/changeHpMp 只需 owner.stats.wLock
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            owner.stats.recalc();
            owner.changeHpMp(owner.stats.getTotal(Stat.MAX_HP), owner.stats.getTotal(Stat.MAX_MP), true);

            List<Pair<PacketStat, Integer>> statup = new ArrayList<>(10);
            statup.add(new Pair<>(PacketStat.AVAILABLEAP, owner.stats.getRemainingAp()));
            statup.add(new Pair<>(PacketStat.AVAILABLESP, owner.sp.remainingSp[CharacterSp.indexOf(owner.job.getId())]));
            statup.add(new Pair<>(PacketStat.HP, owner.stats.getHp()));
            statup.add(new Pair<>(PacketStat.MP, owner.stats.getMp()));
            statup.add(new Pair<>(PacketStat.EXP, exp.get()));
            statup.add(new Pair<>(PacketStat.LEVEL, level));
            statup.add(new Pair<>(PacketStat.MAXHP, owner.stats.getClientMaxHp()));
            statup.add(new Pair<>(PacketStat.MAXMP, owner.stats.getClientMaxMp()));
            statup.add(new Pair<>(PacketStat.STR, owner.stats.getBase(STR)));
            statup.add(new Pair<>(PacketStat.DEX, owner.stats.getBase(DEX)));

            owner.sendPacket(PacketCreator.updatePlayerStats(statup, true, owner));
        }

        owner.getMap().broadcastMessage(owner, PacketCreator.showForeignEffect(owner.getId(), 0), false);
        owner.setMPC(new PartyCharacter(owner));
        owner.silentPartyUpdate();

        if (owner.guild.getGuildId() > 0) {
            owner.getGuild().broadcast(PacketCreator.levelUpMessage(2, level, owner.getName()), owner.getId());
        }

        if (level % 20 == 0) {
            if (GameConfig.getServerBoolean("use_add_slots_by_level")) {
                if (!owner.isGM()) {
                    for (byte i = 1; i < 5; i++) {
                        owner.gainSlots(i, 4, true);
                    }

                    owner.yellowMessage(I18nUtil.getMessage("Character.levelUp.USE_ADD_SLOTS_BY_LEVEL", level));
                }
            }
            if (GameConfig.getServerBoolean("use_add_rates_by_level")) { //For the rate upgrade
                owner.revertLastPlayerRates();
                owner.setPlayerRates();
                owner.yellowMessage(I18nUtil.getMessage("Character.levelUp.USE_ADD_RATES_BY_LEVEL", level));
            }
        }

        if (GameConfig.getServerBoolean("use_perfect_pitch") && level >= 30) {
            //milestones?
            if (InventoryManipulator.checkSpace(owner.client, ItemId.PERFECT_PITCH, (short) 1, "")) {
                InventoryManipulator.addById(owner.client, ItemId.PERFECT_PITCH, (short) 1, "", -1);
            }
        } else if (level == 10) {
            ThreadManager.getInstance().newTask(() -> {
                if (owner.leaveParty()) {
                    owner.showHint(I18nUtil.getMessage("Character.levelUp.LeaveStarterParty"));
                }
            });
        }

        owner.guild.guildUpdate();

        FamilyEntry familyEntry = owner.family.getFamilyEntry();
        if (familyEntry != null) {
            familyEntry.giveReputationToSenior(GameConfig.getServerInt("family_rep_per_level_up"), true);
            FamilyEntry senior = familyEntry.getSenior();
            if (senior != null) { //only send the message to direct senior
                Character seniorChr = senior.getChr();
                if (seniorChr != null) {
                    seniorChr.sendPacket(PacketCreator.levelUpMessage(1, level, owner.getName()));
                }
            }
        }

        owner.updateMobExpRate();
    }

    // ── 查询 ──

    int getLevel() {
        return level;
    }

    void setLevel(int level) {
        this.level = level;
    }

    void setExp(int amount) {
        exp.set(amount);
    }

    void setGachaExp(int amount) {
        gachaExp.set(amount);
    }
}
