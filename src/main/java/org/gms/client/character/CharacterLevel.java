package org.gms.client.character;

import org.gms.client.Disease;
import org.gms.client.FamilyEntry;
import org.gms.client.PacketStat;

import org.gms.config.GameConfig;
import org.gms.constants.game.ExpTable;
import org.gms.constants.game.GameConstants;
import org.gms.constants.net.ServerConstants;
import org.gms.constants.id.ItemId;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.server.ThreadManager;
import org.gms.server.ExpLogger;
import org.gms.server.ExpLogger.ExpLogRecord;
import org.gms.server.life.PlayerNPC;
import org.gms.net.server.world.PartyCharacter;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
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
    private long exp;
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

        int levelUpNeed = ExpTable.getExpNeededForLevel(level) - (int) exp;
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

    /**
     * 语义经验路径（新 path，与 legacy gainExp 家族完全独立——不复用不互调，状态逻辑
     * 就地复制）：状态应用 + exp 数值帧（updateSingleStat）+ 演出帧（GainExpEvent，显示
     * 形态由版本 translator 按 source 决定）。
     * GameConfig 分支按默认设置写死：use_level_up_protect=true（一次调用至多升一级）、
     * use_announce_global_level_up=false（无全服升级广播）、use_exp_gain_log=false
     * （无 ExpLogger 记账）。
     */
    public void gainExp(int gain, ExpSource source) {
        if (gain <= 0) {
            log.warn("gainExp: 零/负增量 {}（source {}）不处理", gain, source);
            return;
        }
        if (owner.hasDisease(Disease.CURSE)) {  // fixme: [refactor] only for mobs?
            gain *= 0.5;   // 诅咒减半（与 legacy 同规，含演出数值）
        }
        if (gain == 0) {
            return;
        }
        applyExp(gain, source);
    }

    /**
     * 状态应用核心（player strand 串行执行，无锁——actor 纪律见 doc/07 §Player/PlayerStrand）。
     * exp 数值帧并入 GainExpEvent（gain/totalExp/source → 版本 translator 双包：数值帧 +
     * 演出帧，序同 legacy updateSingleStat → announceExpGain）；leftover 续算各发各自事件。
     * GameConfig 写死同前：use_level_up_protect 视作 false（分支删除，一次调用可连升）、
     * use_announce_global_level_up=false、use_exp_gain_log=false。
     */
    private void applyExp(int gain, ExpSource source) {
        if (level >= owner.getMaxLevel() || !(owner.allowExpGain || owner.getEventInstance() != null)) {
            return;   // 满级 / 经验获取禁止：状态与演出皆无（legacy 同规）
        }
        exp += gain;

        // exp 数值帧（STAT_CHANGED exp，translator 截断 int 上限）+ 演出帧，升级循环之前
        owner.remote().basic().gainExp(gain, exp, source);

        while (exp >= ExpTable.getExpNeededForLevel(level)) {
            levelUp(true);
            if (level == owner.getMaxLevel()) {
                exp = 0;
                owner.updateSingleStat(PacketStat.EXP, 0);   // 满级清零帧暂留 legacy（后续并入 levelUp）
            }
        }
        // owner.lastExpGainTime = System.currentTimeMillis();
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

        // TODO [refactor] equip 行曾由精灵吊坠独占；其加成现并入桶倍率（RateBucket.EQUIP），如需独立经验行再恢复
        gainExpInternal(gain, 0, party, show, inChat, white);
    }

    public void loseExp(int loss, boolean show, boolean inChat) {
        loseExp(loss, show, inChat, true);
    }

    public void loseExp(int loss, boolean show, boolean inChat, boolean white) {
        gainExpInternal(-loss, 0, 0, show, inChat, white);
    }

    private synchronized void gainExpInternal(long gain, int equip, int party, boolean show, boolean inChat, boolean white) {   // need of method synchonization here detected thanks to MedicOP
        long total = Math.max(gain + equip + party, -exp);

        if (level < owner.getMaxLevel() && (owner.allowExpGain || owner.getEventInstance() != null)) {
            long leftover = 0;
            long nextExp = exp + total;

            if (nextExp > (long) Integer.MAX_VALUE) {
                total = Integer.MAX_VALUE - exp;
                leftover = nextExp - Integer.MAX_VALUE;
            }
            exp += (int) total;
            owner.updateSingleStat(PacketStat.EXP, (int) exp);
            totalExpGained += total;
            if (show) {
                announceExpGain(gain, equip, party, inChat, white);
            }
            while (exp >= ExpTable.getExpNeededForLevel(level)) {
                levelUp(true);

                String msg = I18nUtil.getMessage("Character.levelUp.globalNotice", owner.getName(), owner.getMapRef().getMapName(), getLevel());
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
                    exp = 0;
                    owner.updateSingleStat(PacketStat.EXP, 0);
                    break;
                }
                if (GameConfig.getServerBoolean("use_level_up_protect")) break;
            }

            if (leftover > 0) {
                gainExpInternal(leftover, equip, party, false, inChat, white);
            } else {
                // owner.lastExpGainTime = System.currentTimeMillis();

                if (GameConfig.getServerBoolean("use_exp_gain_log")) {
                    ExpLogRecord expLogRecord = new ExpLogger.ExpLogRecord(
                            owner.getWorldServer().getExpRate(),
                            owner.getCouponExpRate(),
                            totalExpGained,
                            (int) Math.min(exp, Integer.MAX_VALUE),
                            new Timestamp(0),
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
        return (int) Math.min(exp, Integer.MAX_VALUE);   // long exp → int 消费点截断（20 亿）
    }

    public int getGachaExp() {
        return gachaExp.get();
    }

    public void levelUp(boolean takeexp) {
        // 一个语义域（升级）：全程变更（授予/自动分配/满血满蓝/等级/经验）的公告自动合并为
        // 一个净 diff 包，未变化字段不出现——替代旧的全量 statup 拼装
        try (var _u = owner.remote().batch()) {
            // 职业强相关授予（新手自动分配 / maxHp·maxMp·AP·SP / 技能加成 / INT 加成）全在 CharacterJob
            owner.job.applyLevelUpRewards(level + 1);

            if (takeexp) {
                exp -= ExpTable.getExpNeededForLevel(level);
                if (exp < 0) {
                    exp = 0;
                }
            }

            level++;
            if (level >= owner.getMaxLevel()) {
                exp = 0;

                int maxClassLevel = owner.getMaxLevel();
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

            // effLock/wLock 均已冗余：strand 串行 + stats 直写（原 stats.wLock 随快照机制退役）
            // fixme: [refactor] add heal hp/mp
            owner.stats.recalc();
            owner.stats.setHp(owner.stats.getTotal(Stat.MAX_HP));   // 外层 _u batch 内，自动合并单包
            owner.stats.setMp(owner.stats.getTotal(Stat.MAX_MP));
            owner.remote().basic().updateLevel(level);
            owner.remote().basic().updateExp(exp);   // 与上一调用同段（外层 _u batch）合并为一个 STAT_CHANGED
        }   // try-with-resources close = 统一发送

        owner.getMapRef().broadcastMessage(owner.ref(), PacketCreator.showForeignEffect(owner.getId(), 0), false);
        owner.setMPC(new PartyCharacter(owner));
        owner.silentPartyUpdate();

        if (owner.guild.getGuildId() > 0) {
            owner.getGuild().broadcast(PacketCreator.levelUpMessage(2, level, owner.getName()), owner.getId());
        }

        // if (level == 10) {
        //     ThreadManager.getInstance().newTask(() -> {
        //         if (owner.leaveParty()) {
        //             owner.showHint(I18nUtil.getMessage("Character.levelUp.LeaveStarterParty"));
        //         }
        //     });
        // }

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

        // owner.updateMobExpRate();
    }

    // ── 查询 ──

    int getLevel() {
        return level;
    }

    void setLevel(int level) {
        this.level = level;
    }

    void setExp(int amount) {
        exp = amount;
    }

    void setGachaExp(int amount) {
        gachaExp.set(amount);
    }
}
