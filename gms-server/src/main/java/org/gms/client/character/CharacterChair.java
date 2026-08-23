package org.gms.client.character;

import org.gms.client.JobEnum;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.skills.Beginner;
import org.gms.constants.skills.Legend;
import org.gms.constants.skills.Noblesse;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 座位（椅子）模块组件：当前椅子 id + 椅子恢复任务 + 椅子 buff 注册/注销 + 坐下/站起 + 恢复参数。
 * 仿照 CharacterBuffs/CharacterPets 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * 自有锁（lock）串行化（原实现用 chrLock + AtomicInteger），Character 保留公开具名门面。
 *
 * 锁说明：原 chair 用 AtomicInteger、chairRecoveryTask 用 chrLock、恢复参数在 CharacterStats 由
 * rLock/wLock 保护；重构收敛为本类自有的 lock（chair 状态 + 恢复任务 + 恢复参数同锁），
 * 与 chrLock 无交互；恢复参数原随 stats 重算周期重置（recalc），迁移后由本类持有。
 */
class CharacterChair {
    private final Character owner;

    /** 当前椅子 id，-1 = 未坐下 */
    private int chair = -1;

    /** 椅子恢复定时任务（HP/MP 缓慢回复） */
    private ScheduledFuture<?> chairRecoveryTask = null;

    /** 椅子恢复参数：-1 = 需重算（updateChairHealStats 中惰性计算后填充） */
    private int healRate = -1;
    private int healHp;
    private int healMp;

    /** 座位模块锁：串行化椅子状态、恢复任务与恢复参数 */
    private final Lock lock = new ReentrantLock(true);

    CharacterChair(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    int getChair() {
        try (var ignored = Locks.acquire(lock)) {
            return chair;
        }
    }

    /** 是否已坐下职业椅子（sendSpawnData 判定是否需要广播椅子技能效果） */
    boolean hasMapChairBuff() {
        int skillId = getJobMapChair(owner.getJob());
        return owner.getSkillLevel(skillId) > 0 && owner.buffs.entries.containsKey(skillId);
    }

    // ── 坐下/站起 ──

    void sitChair(int itemId) {
        if (owner.isLoggedInWorld()) {
            if (itemId >= 1000000) {    // sit on item chair
                if (getChair() < 0) {
                    setChair(itemId);
                    owner.getMap().broadcastMessage(owner, PacketCreator.showChair(owner.getId(), itemId), false);
                }
                owner.enableActions();
            } else if (itemId >= 0) {    // sit on map chair
                if (getChair() < 0) {
                    setChair(itemId);
                    if (registerChairBuff()) {
                        owner.getMap().broadcastMessage(owner, PacketCreator.giveForeignChairSkillEffect(owner.getId()), false);
                    }
                    owner.sendPacket(PacketCreator.cancelChair(itemId));
                }
            } else {    // stand up
                unsitChairInternal();
            }
        }
    }

    void unsitChairInternal() {
        int chairid = getChair();
        if (chairid >= 0) {
            if (ItemConstants.isFishingChair(chairid)) {
                owner.getWorldServer().unregisterFisherPlayer(owner);
            }

            setChair(-1);
            if (unregisterChairBuff()) {
                owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignChairSkillEffect(owner.getId()), false);
            }

            owner.getMap().broadcastMessage(owner, PacketCreator.showChair(owner.getId(), 0), false);
        }

        owner.sendPacket(PacketCreator.cancelChair(-1));
    }

    /** 换图/离线时清座位（leaveMap/cleanup 用；不广播，地图已切走） */
    void clearChair() {
        try (var ignored = Locks.acquire(lock)) {
            chair = -1;
        }
    }

    private void setChair(int chair) {
        try (var ignored = Locks.acquire(lock)) {
            this.chair = chair;
        }
    }

    // ── 椅子 buff（职业椅子技能：额外的坐下回血） ──

    private static int getJobMapChair(JobEnum job) {
        return switch (job.getId() / 1000) {
            case 0 -> Beginner.MAP_CHAIR;
            case 1 -> Noblesse.MAP_CHAIR;
            default -> Legend.MAP_CHAIR;
        };
    }

    boolean registerChairBuff() {
        if (!GameConfig.getServerBoolean("use_chair_extra_heal")) {
            return false;
        }

        int skillId = getJobMapChair(owner.getJob());
        int skillLv = owner.getSkillLevel(skillId);
        if (skillLv > 0) {
            BuffEffectData mapChairSkill = SkillFactory.getSkill(skillId).getEffect(skillLv);
            mapChairSkill.applyTo(owner);
            return true;
        }

        return false;
    }

    boolean unregisterChairBuff() {
        if (!GameConfig.getServerBoolean("use_chair_extra_heal")) {
            return false;
        }

        int skillId = getJobMapChair(owner.getJob());
        int skillLv = owner.getSkillLevel(skillId);
        if (skillLv > 0) {
            BuffEffectData mapChairSkill = SkillFactory.getSkill(skillId).getEffect(skillLv);
            return owner.cancelEffect(mapChairSkill, false);
        }

        return false;
    }

    // ── 椅子恢复任务 ──

    void stopChairTask() {
        try (var ignored = Locks.acquire(lock)) {
            if (chairRecoveryTask != null) {
                chairRecoveryTask.cancel(false);
                chairRecoveryTask = null;
            }
        }
    }

    void startChairTask() {
        if (getChair() < 0) {
            return;
        }

        updateChairHealStats();
        int healInterval;
        try (var ignored = Locks.acquire(lock)) {
            healInterval = healRate;
            if (chairRecoveryTask != null) {
                chairRecoveryTask.cancel(false);
            }

            chairRecoveryTask = TimerManager.getInstance().register(() -> {
                updateChairHealStats();
                final int healHP = healHp;
                final int healMP = healMp;

                if (owner.getHp() < owner.stats.getTotal(Stat.MAX_HP)) {
                    byte recHP = (byte) (healHP / 10);

                    owner.sendPacket(PacketCreator.showOwnRecovery(recHP));
                    owner.getMap().broadcastMessage(owner, PacketCreator.showRecovery(owner.getId(), recHP), false);
                } else if (owner.getMp() >= owner.stats.getTotal(Stat.MAX_MP)) {
                    stopChairTask();    // optimizing schedule management when player is already with full pool.
                }

                owner.addMPHP(healHP, healMP);
            }, healInterval, healInterval);
        }
    }

    /** 惰性计算并缓存椅子恢复参数（healRate/healHp/healMp），已算好则跳过 */
    private void updateChairHealStats() {
        try (var ignored = Locks.acquire(lock)) {
            if (healRate != -1) {
                return;
            }
            Pair<Integer, Pair<Integer, Integer>> p = getChairTaskIntervalRate(owner.stats.getTotal(Stat.MAX_HP), owner.stats.getTotal(Stat.MAX_MP));
            healRate = p.getLeft();
            healHp = p.getRight().getLeft();
            healMp = p.getRight().getRight();
        }
    }

    /** 装备/属性变化后使恢复参数失效（Character.recalc 调用），下次 startChairTask 时重算 */
    void invalidateHealStats() {
        try (var ignored = Locks.acquire(lock)) {
            healRate = -1;
        }
    }

    private static Pair<Integer, Pair<Integer, Integer>> getChairTaskIntervalRate(int maxhp, int maxmp) {
        /*
        此处2个参数CHAIR_EXTRA_HEAL_MULTIPLIER和CHAIR_EXTRA_HEAL_MAX_DELAY已被我删除
        1.在倍率固定的情况下，既不希望定时任务执行太快，又不希望定时任务执行太慢
        2.在固定最大时间的情况下，既不希望恢复量太大，又不希望恢复量太小
        3.关键是这2个参数又都能配置，在某些场景下，就会打破上述他自己设置的限制
        4.所以，这个参数需要个人进行复杂的计算才能配置，不能乱配，但他又放开让你都允许配置
        5.综上，这种属于既要又要，什么都要只会害了你，所以我把这2个参数都干掉了
        6.如果确实要放开允许配置，最多把CHAIR_EXTRA_HEAL_MAX_DELAY放开即可，这个参数还算有点意义，但也需要简单计算一下得到他合适的值
         */
        float toHeal = Math.max(maxhp, maxmp);
        float maxDuration = SECONDS.toMillis(21);

        int rate = 0;
        int minRegen = 1, maxRegen = 2559, midRegen = 1;
        while (minRegen < maxRegen) {
            midRegen = (int) ((minRegen + maxRegen) * 0.94);

            float procs = toHeal / midRegen;
            float newRate = maxDuration / procs;
            rate = (int) newRate;

            if (newRate < 420) {
                minRegen = (int) (1.2 * midRegen);
            } else if (newRate > 5000) {
                maxRegen = (int) (0.8 * midRegen);
            } else {
                break;
            }
        }

        float procs = maxDuration / rate;
        int hpRegen, mpRegen;
        if (maxhp > maxmp) {
            hpRegen = midRegen;
            mpRegen = (int) Math.ceil(maxmp / procs);
        } else {
            hpRegen = (int) Math.ceil(maxhp / procs);
            mpRegen = midRegen;
        }

        return new Pair<>(rate, new Pair<>(hpRegen, mpRegen));
    }
}
