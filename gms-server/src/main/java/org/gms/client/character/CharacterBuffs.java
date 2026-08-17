package org.gms.client.character;

import org.gms.client.BuffStat;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.net.server.PlayerBuffValueHolder;
import org.gms.net.server.Server;
import org.gms.server.ItemInformationProvider;
import org.gms.server.StatEffect;
import org.gms.server.TimerManager;
import org.gms.server.ItemInformationProvider;
import org.gms.util.PacketCreator;
import org.gms.constants.id.ItemId;
import org.gms.constants.skills.DarkKnight;
import org.gms.constants.skills.Priest;
import org.gms.server.maps.Door;
import org.gms.server.maps.Summon;
import org.gms.util.Locks;
import org.gms.util.Pair;
import org.gms.util.TimeoutHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Stack;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * buff 机制层：槽位（BuffStat）存储 + 同槽位最佳效果选择 + 到期触发 + 查询。
 *
 * 机制：每种 buff 属于 1..n 个槽位；获得新 buff 时旧 buff 仍在服务端在册
 * （buffEffects 按源分组），服务端把同槽位效果最好的一个写入 effects 报告给客户端；
 * 最佳效果被取消时（bestApplied 标记）从未结束的同槽位 buff 中重选（fetchBest...）。
 *
 * 编排层（registerEffect/cancelEffect 及其伴随调度）留在 Character；
 * debuff/disease 与 visual effect 不在此管理。
 */
class CharacterBuffs {
    private static final Logger log = LoggerFactory.getLogger(CharacterBuffs.class);

    private final Character owner;

    /** 每槽位当前激活的最佳效果（"IN ACTION"） */
    final EnumMap<BuffStat, BuffStatValueHolder> effects = new EnumMap<>(BuffStat.class);
    /** 每槽位在册计数 */
    final Map<BuffStat, Byte> buffEffectsCount = new LinkedHashMap<>();
    /** 全部在册效果（含被压制的同槽位旧 buff），key = buffSourceId */
    final Map<Integer, Map<BuffStat, BuffStatValueHolder>> buffEffects = new LinkedHashMap<>();
    /** 每源到期时刻 */
    final Map<Integer, Long> buffExpires = new LinkedHashMap<>();

    /** buff 到期定时器（id = sourceid，timestamp = 到期时刻），替代原 1.5s 周期扫描 */
    private final TimeoutHelper expireTimer = new TimeoutHelper();

    CharacterBuffs(Character owner) {
        this.owner = owner;
        expireTimer.setListener((sourceid, timestamp) -> {
            BuffStatValueHolder mbsvh;
            try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
                Map<BuffStat, BuffStatValueHolder> be = buffEffects.get(sourceid);
                if (be == null || be.isEmpty()) {
                    return;
                }
                mbsvh = be.entrySet().iterator().next().getValue();
            }
            owner.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
        });
    }

    void startExpireTimer() {
        expireTimer.start();
        // 登录补排：静默恢复的 buff 在 start 前入库（schedule 被 TimeoutHelper 丢弃）
        List<Map.Entry<Integer, Long>> es;
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            es = new ArrayList<>(buffExpires.entrySet());
        }
        for (Map.Entry<Integer, Long> e : es) {
            expireTimer.scheduleOrTrigger(e.getKey(), e.getValue());
        }
    }

    void stopExpireTimer() {
        expireTimer.stop();
    }

    // ── 查询 ──

    Long getBuffedStarttime(BuffStat effect) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            BuffStatValueHolder mbsvh = effects.get(effect);
            if (mbsvh == null) {
                return null;
            }
            return mbsvh.startTime;
        }
    }

    Integer getBuffedValue(BuffStat effect) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            BuffStatValueHolder mbsvh = effects.get(effect);
            if (mbsvh == null) {
                return null;
            }
            return mbsvh.value;
        }
    }

    int getBuffSource(BuffStat stat) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            BuffStatValueHolder mbsvh = effects.get(stat);
            if (mbsvh == null) {
                return -1;
            }
            return mbsvh.effect.getSourceId();
        }
    }

    StatEffect getBuffEffect(BuffStat stat) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            BuffStatValueHolder mbsvh = effects.get(stat);
            return mbsvh == null ? null : mbsvh.effect;
        }
    }

    boolean isBuffFrom(BuffStat stat, org.gms.client.Skill skill) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            BuffStatValueHolder mbsvh = effects.get(stat);
            if (mbsvh == null) {
                return false;
            }
            return mbsvh.effect.isSkill() && mbsvh.effect.getSourceId() == skill.getId();
        }
    }

    void setBuffedValue(BuffStat effect, int value) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            BuffStatValueHolder mbsvh = effects.get(effect);
            if (mbsvh == null) {
                return;
            }
            mbsvh.value = value;
        }
    }

    List<PlayerBuffValueHolder> getAllBuffs() {  // buff values will be stored in an arbitrary order
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            long curtime = Server.getInstance().getCurrentTime();

            Map<Integer, PlayerBuffValueHolder> ret = new LinkedHashMap<>();
            for (Map<BuffStat, BuffStatValueHolder> bel : buffEffects.values()) {
                for (BuffStatValueHolder mbsvh : bel.values()) {
                    int srcid = mbsvh.effect.getBuffSourceId();
                    if (!ret.containsKey(srcid)) {
                        ret.put(srcid, new PlayerBuffValueHolder((int) (curtime - mbsvh.startTime), mbsvh.effect));
                    }
                }
            }
            return new ArrayList<>(ret.values());
        }
    }

    boolean hasBuffFromSourceid(int sourceid) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            return buffEffects.containsKey(sourceid);
        }
    }

    boolean hasActiveBuff(int sourceid) {
        LinkedList<BuffStatValueHolder> allBuffs;
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            allBuffs = new LinkedList<>(effects.values());
        }

        for (BuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.getBuffSourceId() == sourceid) {
                return true;
            }
        }
        return false;
    }

    List<BuffStatValueHolder> getAllStatups() {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            List<BuffStatValueHolder> ret = new ArrayList<>();
            for (Map<BuffStat, BuffStatValueHolder> bel : buffEffects.values()) {
                ret.addAll(bel.values());
            }
            return ret;
        }
    }

    /** 已在 effLock & chrLock 内调用 */
    List<Pair<BuffStat, Integer>> getActiveStatupsFromSourceid(int sourceid) {
        List<Pair<BuffStat, Integer>> ret = new ArrayList<>();
        List<Pair<BuffStat, Integer>> singletonStatups = new ArrayList<>();
        for (Map.Entry<BuffStat, BuffStatValueHolder> bel : buffEffects.get(sourceid).entrySet()) {
            BuffStat mbs = bel.getKey();
            BuffStatValueHolder mbsvh = effects.get(bel.getKey());

            Pair<BuffStat, Integer> p;
            if (mbsvh != null) {
                p = new Pair<>(mbs, mbsvh.value);
            } else {
                p = new Pair<>(mbs, 0);
            }

            if (!isSingletonStatup(mbs)) {   // thanks resinate, Daddy Egg for pointing out morph issues when updating it along with other statups
                ret.add(p);
            } else {
                singletonStatups.add(p);
            }
        }

        ret.sort(Comparator.comparing(Pair::getLeft));

        if (!singletonStatups.isEmpty()) {
            singletonStatups.sort(Comparator.comparing(Pair::getLeft));

            ret.addAll(singletonStatups);
        }

        return ret;
    }

    void debugListAllBuffs() {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            log.debug("-------------------");
            log.debug("CACHED BUFF COUNT: {}", buffEffectsCount.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining(", "))
            );

            log.debug("-------------------");
            log.debug("CACHED BUFFS: {}", buffEffects.entrySet().stream()
                    .map(entry -> entry.getKey() + ": (" + entry.getValue().entrySet().stream()
                            .map(innerEntry -> innerEntry.getKey().name() + innerEntry.getValue().value)
                            .collect(Collectors.joining(", ")) + ")")
                    .collect(Collectors.joining(", "))
            );

            log.debug("-------------------");
            log.debug("IN ACTION: {}", effects.entrySet().stream()
                    .map(entry -> entry.getKey().name() + " -> " + ItemInformationProvider.getInstance().getName(entry.getValue().effect.getSourceId()))
                    .collect(Collectors.joining(", "))
            );
        }
    }

    // ── 槽位存储与选择 ──

    void addItemEffectHolder(Integer sourceid, long expirationtime, Map<BuffStat, BuffStatValueHolder> statups) {
        buffEffects.put(sourceid, statups);
        buffExpires.put(sourceid, expirationtime);
        expireTimer.schedule(sourceid, expirationtime);
    }

    boolean removeEffectFromItemEffectHolder(Integer sourceid, BuffStat buffStat) {
        Map<BuffStat, BuffStatValueHolder> lbe = buffEffects.get(sourceid);

        if (lbe.remove(buffStat) != null) {
            buffEffectsCount.put(buffStat, (byte) (buffEffectsCount.get(buffStat) - 1));

            if (lbe.isEmpty()) {
                buffEffects.remove(sourceid);
                buffExpires.remove(sourceid);
                expireTimer.cancel(sourceid);
            }

            return true;
        }

        return false;
    }

    void removeItemEffectHolder(Integer sourceid) {
        Map<BuffStat, BuffStatValueHolder> be = buffEffects.remove(sourceid);
        if (be != null) {
            for (Map.Entry<BuffStat, BuffStatValueHolder> bei : be.entrySet()) {
                buffEffectsCount.put(bei.getKey(), (byte) (buffEffectsCount.get(bei.getKey()) - 1));
            }
        }

        buffExpires.remove(sourceid);
        expireTimer.cancel(sourceid);
    }

    /** 同槽位重选最佳：value 最大者优先，同值取 statups 更多者；选中者写入 effects */
    BuffStatValueHolder fetchBestEffectFromItemEffectHolder(BuffStat mbs) {
        Pair<Integer, Integer> max = new Pair<>(Integer.MIN_VALUE, 0);
        BuffStatValueHolder mbsvh = null;
        for (Map.Entry<Integer, Map<BuffStat, BuffStatValueHolder>> bpl : buffEffects.entrySet()) {
            BuffStatValueHolder mbsvhi = bpl.getValue().get(mbs);
            if (mbsvhi != null) {
                if (!mbsvhi.effect.isActive(owner)) {
                    continue;
                }

                if (mbsvhi.value > max.left) {
                    max = new Pair<>(mbsvhi.value, mbsvhi.effect.getStatups().size());
                    mbsvh = mbsvhi;
                } else if (mbsvhi.value == max.left && mbsvhi.effect.getStatups().size() > max.right) {
                    max = new Pair<>(mbsvhi.value, mbsvhi.effect.getStatups().size());
                    mbsvh = mbsvhi;
                }
            }
        }

        if (mbsvh != null) {
            effects.put(mbs, mbsvh);
        }
        return mbsvh;
    }

    void extractBuffValue(int sourceid, BuffStat stat) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            removeEffectFromItemEffectHolder(sourceid, stat);
        }
    }

    Map<BuffStat, BuffStatValueHolder> extractCurrentBuffStats(StatEffect effect) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            Map<BuffStat, BuffStatValueHolder> stats = new LinkedHashMap<>();
            Map<BuffStat, BuffStatValueHolder> buffList = buffEffects.remove(effect.getBuffSourceId());

            if (buffList != null) {
                for (Map.Entry<BuffStat, BuffStatValueHolder> stateffect : buffList.entrySet()) {
                    stats.put(stateffect.getKey(), stateffect.getValue());
                    buffEffectsCount.put(stateffect.getKey(), (byte) (buffEffectsCount.get(stateffect.getKey()) - 1));
                }
            }

            return stats;
        }
    }

    Map<BuffStat, BuffStatValueHolder> extractLeastRelevantStatEffectsIfFull(StatEffect effect) {
        Map<BuffStat, BuffStatValueHolder> extractedStatBuffs = new LinkedHashMap<>();

        try (var ignored = Locks.acquire(owner.chrLock)) {
            Map<BuffStat, Byte> stats = new LinkedHashMap<>();
            Map<BuffStat, BuffStatValueHolder> minStatBuffs = new LinkedHashMap<>();

            for (Map.Entry<Integer, Map<BuffStat, BuffStatValueHolder>> mbsvhi : buffEffects.entrySet()) {
                for (Map.Entry<BuffStat, BuffStatValueHolder> mbsvhe : mbsvhi.getValue().entrySet()) {
                    BuffStat mbs = mbsvhe.getKey();
                    Byte b = stats.get(mbs);

                    if (b != null) {
                        stats.put(mbs, (byte) (b + 1));
                        if (mbsvhe.getValue().value < minStatBuffs.get(mbs).value) {
                            minStatBuffs.put(mbs, mbsvhe.getValue());
                        }
                    } else {
                        stats.put(mbs, (byte) 1);
                        minStatBuffs.put(mbs, mbsvhe.getValue());
                    }
                }
            }

            Set<BuffStat> effectStatups = new LinkedHashSet<>();
            for (Pair<BuffStat, Integer> efstat : effect.getStatups()) {
                effectStatups.add(efstat.getLeft());
            }

            for (Map.Entry<BuffStat, Byte> it : stats.entrySet()) {
                boolean uniqueBuff = isSingletonStatup(it.getKey());

                if (it.getValue() >= (!uniqueBuff ? GameConfig.getServerByte("max_monitored_buff_stats") : 1) && effectStatups.contains(it.getKey())) {
                    BuffStatValueHolder mbsvh = minStatBuffs.get(it.getKey());

                    Map<BuffStat, BuffStatValueHolder> lpbe = buffEffects.get(mbsvh.effect.getBuffSourceId());
                    lpbe.remove(it.getKey());
                    buffEffectsCount.put(it.getKey(), (byte) (buffEffectsCount.get(it.getKey()) - 1));

                    if (lpbe.isEmpty()) {
                        buffEffects.remove(mbsvh.effect.getBuffSourceId());
                    }
                    extractedStatBuffs.put(it.getKey(), mbsvh);
                }
            }
        }

        return extractedStatBuffs;
    }

    void addItemEffectHolderCount(BuffStat stat) {
        Byte val = buffEffectsCount.get(stat);
        if (val != null) {
            val = (byte) (val + 1);
        } else {
            val = (byte) 1;
        }

        buffEffectsCount.put(stat, val);
    }

    // ── 全量取消 ──

    void cancelAllBuffs(boolean softcancel) {
        if (softcancel) {
            try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
                owner.cancelEffectFromBuffStat(BuffStat.SUMMON);
                owner.cancelEffectFromBuffStat(BuffStat.PUPPET);
                owner.cancelEffectFromBuffStat(BuffStat.COMBO);

                effects.clear();

                for (Integer srcid : new ArrayList<>(buffEffects.keySet())) {
                    removeItemEffectHolder(srcid);
                }
            }
        } else {
            Map<StatEffect, Long> mseBuffs = new LinkedHashMap<>();

            try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
                for (Map.Entry<Integer, Map<BuffStat, BuffStatValueHolder>> bpl : buffEffects.entrySet()) {
                    for (Map.Entry<BuffStat, BuffStatValueHolder> mbse : bpl.getValue().entrySet()) {
                        mseBuffs.put(mbse.getValue().effect, mbse.getValue().startTime);
                    }
                }
            }

            for (Map.Entry<StatEffect, Long> mse : mseBuffs.entrySet()) {
                owner.cancelEffect(mse.getKey(), false, mse.getValue());
            }
        }
    }

    // ── 静态判定 ──

    static boolean isSingletonStatup(BuffStat mbs) {
        return switch (mbs) {           //HPREC and MPREC are supposed to be singleton
            case COUPON_EXP1, COUPON_EXP2, COUPON_EXP3, COUPON_EXP4, COUPON_DRP1, COUPON_DRP2, COUPON_DRP3,
                 MESO_UP_BY_ITEM,
                 ITEM_UP_BY_ITEM, RESPECT_PIMMUNE, RESPECT_MIMMUNE, DEFENSE_ATT, DEFENSE_STATE, WATK, WDEF, MATK, MDEF,
                 ACC, AVOID, SPEED, JUMP -> false;
            default -> true;
        };
    }

    static boolean isPriorityBuffSourceId(int sourceId) {
        return -org.gms.constants.id.ItemId.ROSE_SCENT == sourceId || -org.gms.constants.id.ItemId.FREESIA_SCENT == sourceId || -org.gms.constants.id.ItemId.LAVENDER_SCENT == sourceId;
    }

    // ── 编排层：注册/取消/传播（原 Character 平移，Character.this → owner） ──

    private void cancelPlayerBuffs(List<BuffStat> buffstats) {
        if (owner.client.getChannelServer().getPlayerStorage().getCharacterById(owner.getId()) != null) {
            owner.updateLocalStats();
            owner.sendPacket(PacketCreator.cancelBuff(buffstats));
            if (!buffstats.isEmpty()) {
                owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignBuff(owner.getId(), buffstats), false);
            }
        }
    }

    private void dropBuffStats(List<Pair<BuffStat, BuffStatValueHolder>> effectsToCancel) {
        for (Pair<BuffStat, BuffStatValueHolder> cancelEffectCancelTasks : effectsToCancel) {
            //boolean nestedCancel = false;

            owner.chrLock.lock();
            try {
                /*
                if (buffExpires.get(cancelEffectCancelTasks.getRight().effect.getBuffSourceId()) != null) {
                    nestedCancel = true;
                }*/

                if (cancelEffectCancelTasks.getRight().bestApplied) {
                    fetchBestEffectFromItemEffectHolder(cancelEffectCancelTasks.getLeft());
                }
            } finally {
                owner.chrLock.unlock();
            }

            /*
            if (nestedCancel) {
                this.cancelEffect(cancelEffectCancelTasks.getRight().effect, false, -1, false);
            }*/
        }
    }

    private List<Pair<BuffStat, BuffStatValueHolder>> deregisterBuffStats(Map<BuffStat, BuffStatValueHolder> stats) {
        owner.chrLock.lock();
        try {
            List<Pair<BuffStat, BuffStatValueHolder>> effectsToCancel = new ArrayList<>(stats.size());
            for (Entry<BuffStat, BuffStatValueHolder> stat : stats.entrySet()) {
                int sourceid = stat.getValue().effect.getBuffSourceId();

                if (!buffEffects.containsKey(sourceid)) {
                    buffExpires.remove(sourceid);
                }

                BuffStat mbs = stat.getKey();
                effectsToCancel.add(new Pair<>(mbs, stat.getValue()));

                BuffStatValueHolder mbsvh = effects.get(mbs);
                if (mbsvh != null && mbsvh.effect.getBuffSourceId() == sourceid) {
                    mbsvh.bestApplied = true;
                    effects.remove(mbs);

                    if (mbs == BuffStat.RECOVERY) {
                        if (owner.recoveryTask != null) {
                            owner.recoveryTask.cancel(false);
                            owner.recoveryTask = null;
                        }
                    } else if (mbs == BuffStat.SUMMON || mbs == BuffStat.PUPPET) {
                        int summonId = mbsvh.effect.getSourceId();

                        Summon summon = owner.summons.get(summonId);
                        if (summon != null) {
                            owner.getMap().broadcastMessage(PacketCreator.removeSummon(summon, true), summon.getPosition());
                            owner.getMap().removeMapObject(summon);
                            owner.removeVisibleMapObject(summon);

                            owner.summons.remove(summonId);
                            if (summon.isPuppet()) {
                                owner.map.removePlayerPuppet(owner);
                            } else if (summon.getSkill() == DarkKnight.BEHOLDER) {
                                if (owner.beholderHealingSchedule != null) {
                                    owner.beholderHealingSchedule.cancel(false);
                                    owner.beholderHealingSchedule = null;
                                }
                                if (owner.beholderBuffSchedule != null) {
                                    owner.beholderBuffSchedule.cancel(false);
                                    owner.beholderBuffSchedule = null;
                                }
                            }
                        }
                    } else if (mbs == BuffStat.DRAGONBLOOD) {
                        owner.dragonBloodSchedule.cancel(false);
                        owner.dragonBloodSchedule = null;
                    } else if (mbs == BuffStat.HPREC || mbs == BuffStat.MPREC) {
                        if (mbs == BuffStat.HPREC) {
                            owner.extraHpRec = 0;
                        } else {
                            owner.extraMpRec = 0;
                        }

                        if (owner.extraRecoveryTask != null) {
                            owner.extraRecoveryTask.cancel(false);
                            owner.extraRecoveryTask = null;
                        }

                        if (owner.extraHpRec != 0 || owner.extraMpRec != 0) {
                            owner.startExtraTaskInternal(owner.extraHpRec, owner.extraMpRec, owner.extraRecInterval);
                        }
                    }
                }
            }

            return effectsToCancel;
        } finally {
            owner.chrLock.unlock();
        }
    }

    public void cancelEffect(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        cancelEffect(ii.getItemEffect(itemId), false, -1);
    }

    public boolean cancelEffect(StatEffect effect, boolean overwrite, long startTime) {
        boolean ret;

        owner.prtLock.lock();
        owner.effLock.lock();
        try {
            ret = cancelEffect(effect, overwrite, startTime, true);
        } finally {
            owner.effLock.unlock();
            owner.prtLock.unlock();
        }

        if (effect.isMagicDoor() && ret) {
            owner.prtLock.lock();
            owner.effLock.lock();
            try {
                if (!hasBuffFromSourceid(Priest.MYSTIC_DOOR)) {
                    Door.attemptRemoveDoor(owner);
                }
            } finally {
                owner.effLock.unlock();
                owner.prtLock.unlock();
            }
        }

        return ret;
    }

    private boolean cancelEffect(StatEffect effect, boolean overwrite, long startTime, boolean firstCancel) {
        Set<BuffStat> removedStats = new LinkedHashSet<>();
        dropBuffStats(cancelEffectInternal(effect, overwrite, startTime, removedStats));
        owner.updateLocalStats();
        updateEffects(removedStats);

        return !removedStats.isEmpty();
    }

    private List<Pair<BuffStat, BuffStatValueHolder>> cancelEffectInternal(StatEffect effect, boolean overwrite, long startTime, Set<BuffStat> removedStats) {
        Map<BuffStat, BuffStatValueHolder> buffstats = null;
        BuffStat ombs;
        if (!overwrite) {   // is removing the source effect, meaning every effect from this srcid is being purged
            buffstats = extractCurrentBuffStats(effect);
        } else if ((ombs = getSingletonStatupFromEffect(effect)) != null) {   // removing all effects of a buff having non-shareable buff stat.
            BuffStatValueHolder mbsvh = effects.get(ombs);
            if (mbsvh != null) {
                buffstats = extractCurrentBuffStats(mbsvh.effect);
            }
        }

        if (buffstats == null) {            // all else, is dropping ALL current statups that uses same stats as the given effect
            buffstats = extractLeastRelevantStatEffectsIfFull(effect);
        }

        if (effect.isMapChair()) {
            owner.stopChairTask();
        }

        List<Pair<BuffStat, BuffStatValueHolder>> toCancel = deregisterBuffStats(buffstats);
        if (effect.isMonsterRiding()) {
            owner.getClient().getWorldServer().unregisterMountHunger(owner);
            owner.getMapleMount().setActive(false);
        }

        if (!overwrite) {
            removedStats.addAll(buffstats.keySet());
        }

        return toCancel;
    }

    public void cancelEffectFromBuffStat(BuffStat stat) {
        BuffStatValueHolder effect;

        owner.effLock.lock();
        owner.chrLock.lock();
        try {
            effect = effects.get(stat);
        } finally {
            owner.chrLock.unlock();
            owner.effLock.unlock();
        }
        if (effect != null) {
            cancelEffect(effect.effect, false, -1);
        }
    }

    public void cancelBuffStats(BuffStat stat) {
        owner.effLock.lock();
        try {
            List<Pair<Integer, BuffStatValueHolder>> cancelList = new LinkedList<>();

            owner.chrLock.lock();
            try {
                for (Entry<Integer, Map<BuffStat, BuffStatValueHolder>> bel : buffEffects.entrySet()) {
                    BuffStatValueHolder beli = bel.getValue().get(stat);
                    if (beli != null) {
                        cancelList.add(new Pair<>(bel.getKey(), beli));
                    }
                }
            } finally {
                owner.chrLock.unlock();
            }

            Map<BuffStat, BuffStatValueHolder> buffStatList = new LinkedHashMap<>();
            for (Pair<Integer, BuffStatValueHolder> p : cancelList) {
                buffStatList.put(stat, p.getRight());
                extractBuffValue(p.getLeft(), stat);
                dropBuffStats(deregisterBuffStats(buffStatList));
            }
        } finally {
            owner.effLock.unlock();
        }

        cancelPlayerBuffs(Collections.singletonList(stat));
    }

    private void cancelInactiveBuffStats(Set<BuffStat> retrievedStats, Set<BuffStat> removedStats) {
        List<BuffStat> inactiveStats = new LinkedList<>();
        for (BuffStat mbs : removedStats) {
            if (!retrievedStats.contains(mbs)) {
                inactiveStats.add(mbs);
            }
        }

        if (!inactiveStats.isEmpty()) {
            owner.sendPacket(PacketCreator.cancelBuff(inactiveStats));
            owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignBuff(owner.getId(), inactiveStats), false);
        }
    }

    private static Map<StatEffect, Integer> topologicalSortLeafStatCount(Map<BuffStat, Stack<StatEffect>> buffStack) {
        Map<StatEffect, Integer> leafBuffCount = new LinkedHashMap<>();

        for (Entry<BuffStat, Stack<StatEffect>> e : buffStack.entrySet()) {
            Stack<StatEffect> mseStack = e.getValue();
            if (mseStack.isEmpty()) {
                continue;
            }

            StatEffect mse = mseStack.peek();
            leafBuffCount.merge(mse, 1, Integer::sum);
        }

        return leafBuffCount;
    }

    private static List<StatEffect> topologicalSortRemoveLeafStats(Map<StatEffect, Set<BuffStat>> stackedBuffStats, Map<BuffStat, Stack<StatEffect>> buffStack, Map<StatEffect, Integer> leafStatCount) {
        List<StatEffect> clearedStatEffects = new LinkedList<>();
        Set<BuffStat> clearedStats = new LinkedHashSet<>();

        for (Entry<StatEffect, Integer> e : leafStatCount.entrySet()) {
            StatEffect mse = e.getKey();

            if (stackedBuffStats.get(mse).size() <= e.getValue()) {
                clearedStatEffects.add(mse);
                clearedStats.addAll(stackedBuffStats.get(mse));
            }
        }

        for (BuffStat mbs : clearedStats) {
            StatEffect mse = buffStack.get(mbs).pop();
            stackedBuffStats.get(mse).remove(mbs);
        }

        return clearedStatEffects;
    }

    private static void topologicalSortRebaseLeafStats(Map<StatEffect, Set<BuffStat>> stackedBuffStats, Map<BuffStat, Stack<StatEffect>> buffStack) {
        for (Entry<BuffStat, Stack<StatEffect>> e : buffStack.entrySet()) {
            Stack<StatEffect> mseStack = e.getValue();

            if (!mseStack.isEmpty()) {
                StatEffect mse = mseStack.pop();
                stackedBuffStats.get(mse).remove(e.getKey());
            }
        }
    }

    private static List<StatEffect> topologicalSortEffects(Map<BuffStat, List<Pair<StatEffect, Integer>>> buffEffects) {
        Map<StatEffect, Set<BuffStat>> stackedBuffStats = new LinkedHashMap<>();
        Map<BuffStat, Stack<StatEffect>> buffStack = new LinkedHashMap<>();

        for (Entry<BuffStat, List<Pair<StatEffect, Integer>>> e : buffEffects.entrySet()) {
            BuffStat mbs = e.getKey();

            Stack<StatEffect> mbsStack = new Stack<>();
            buffStack.put(mbs, mbsStack);

            for (Pair<StatEffect, Integer> emse : e.getValue()) {
                StatEffect mse = emse.getLeft();
                mbsStack.push(mse);
                Set<BuffStat> mbsStats = stackedBuffStats.computeIfAbsent(mse, k -> new LinkedHashSet<>());
                mbsStats.add(mbs);
            }
        }

        List<StatEffect> buffList = new LinkedList<>();
        while (true) {
            Map<StatEffect, Integer> leafStatCount = topologicalSortLeafStatCount(buffStack);
            if (leafStatCount.isEmpty()) {
                break;
            }

            List<StatEffect> clearedNodes = topologicalSortRemoveLeafStats(stackedBuffStats, buffStack, leafStatCount);
            if (clearedNodes.isEmpty()) {
                topologicalSortRebaseLeafStats(stackedBuffStats, buffStack);
            } else {
                buffList.addAll(clearedNodes);
            }
        }

        return buffList;
    }

    private static List<StatEffect> sortEffectsList(Map<StatEffect, Integer> updateEffectsList) {
        Map<BuffStat, List<Pair<StatEffect, Integer>>> buffEffects = new LinkedHashMap<>();

        for (Entry<StatEffect, Integer> p : updateEffectsList.entrySet()) {
            StatEffect mse = p.getKey();

            for (Pair<BuffStat, Integer> statup : mse.getStatups()) {
                BuffStat stat = statup.getLeft();
                List<Pair<StatEffect, Integer>> statBuffs = buffEffects.computeIfAbsent(stat, k -> new ArrayList<>());
                statBuffs.add(new Pair<>(mse, statup.getRight()));
            }
        }

        for (Entry<BuffStat, List<Pair<StatEffect, Integer>>> statBuffs : buffEffects.entrySet()) {
            statBuffs.getValue().sort((o1, o2) -> o2.getRight().compareTo(o1.getRight()));
        }

        return topologicalSortEffects(buffEffects);
    }

    private List<Pair<Integer, Pair<StatEffect, Long>>> propagatePriorityBuffEffectUpdates(Set<BuffStat> retrievedStats) {
        List<Pair<Integer, Pair<StatEffect, Long>>> priorityUpdateEffects = new LinkedList<>();
        Map<BuffStatValueHolder, StatEffect> yokeStats = new LinkedHashMap<>();

        // priority buffsources: override buffstats for the client to perceive those as "currently buffed"
        Set<BuffStatValueHolder> mbsvhList = new LinkedHashSet<>(getAllStatups());

        for (BuffStatValueHolder mbsvh : mbsvhList) {
            StatEffect mse = mbsvh.effect;
            int buffSourceId = mse.getBuffSourceId();
            if (CharacterBuffs.isPriorityBuffSourceId(buffSourceId) && !hasActiveBuff(buffSourceId)) {
                for (Pair<BuffStat, Integer> ps : mse.getStatups()) {
                    BuffStat mbs = ps.getLeft();
                    if (retrievedStats.contains(mbs)) {
                        BuffStatValueHolder mbsvhe = effects.get(mbs);

                        // this shouldn't even be null...
                        //if (mbsvh != null) {
                        yokeStats.put(mbsvh, mbsvhe.effect);
                        //}
                    }
                }
            }
        }

        for (Entry<BuffStatValueHolder, StatEffect> e : yokeStats.entrySet()) {
            BuffStatValueHolder mbsvhPriority = e.getKey();
            StatEffect mseActive = e.getValue();

            priorityUpdateEffects.add(new Pair<>(mseActive.getBuffSourceId(), new Pair<>(mbsvhPriority.effect, mbsvhPriority.startTime)));
        }

        return priorityUpdateEffects;
    }

    private void propagateBuffEffectUpdates(Map<Integer, Pair<StatEffect, Long>> retrievedEffects, Set<BuffStat> retrievedStats, Set<BuffStat> removedStats) {
        cancelInactiveBuffStats(retrievedStats, removedStats);
        if (retrievedStats.isEmpty()) {
            return;
        }

        Map<BuffStat, Pair<Integer, StatEffect>> maxBuffValue = new LinkedHashMap<>();
        for (BuffStat mbs : retrievedStats) {
            BuffStatValueHolder mbsvh = effects.get(mbs);
            if (mbsvh != null) {
                retrievedEffects.put(mbsvh.effect.getBuffSourceId(), new Pair<>(mbsvh.effect, mbsvh.startTime));
            }

            maxBuffValue.put(mbs, new Pair<>(Integer.MIN_VALUE, null));
        }

        Map<StatEffect, Integer> updateEffects = new LinkedHashMap<>();

        List<StatEffect> recalcMseList = new LinkedList<>();
        for (Entry<Integer, Pair<StatEffect, Long>> re : retrievedEffects.entrySet()) {
            recalcMseList.add(re.getValue().getLeft());
        }

        boolean mageJob = owner.getJobStyle() == Job.MAGICIAN;
        do {
            List<StatEffect> mseList = recalcMseList;
            recalcMseList = new LinkedList<>();

            for (StatEffect mse : mseList) {
                int maxEffectiveStatup = Integer.MIN_VALUE;
                for (Pair<BuffStat, Integer> st : mse.getStatups()) {
                    BuffStat mbs = st.getLeft();

                    boolean relevantStatup = true;
                    if (mbs == BuffStat.WATK) {  // not relevant for mages
                        if (mageJob) {
                            relevantStatup = false;
                        }
                    } else if (mbs == BuffStat.MATK) { // not relevant for non-mages
                        if (!mageJob) {
                            relevantStatup = false;
                        }
                    }

                    Pair<Integer, StatEffect> mbv = maxBuffValue.get(mbs);
                    if (mbv == null) {
                        continue;
                    }

                    if (mbv.getLeft() < st.getRight()) {
                        StatEffect msbe = mbv.getRight();
                        if (msbe != null) {
                            recalcMseList.add(msbe);
                        }

                        maxBuffValue.put(mbs, new Pair<>(st.getRight(), mse));

                        if (relevantStatup) {
                            if (maxEffectiveStatup < st.getRight()) {
                                maxEffectiveStatup = st.getRight();
                            }
                        }
                    }
                }

                updateEffects.put(mse, maxEffectiveStatup);
            }
        } while (!recalcMseList.isEmpty());

        List<StatEffect> updateEffectsList = sortEffectsList(updateEffects);

        List<Pair<Integer, Pair<StatEffect, Long>>> toUpdateEffects = new LinkedList<>();
        for (StatEffect mse : updateEffectsList) {
            toUpdateEffects.add(new Pair<>(mse.getBuffSourceId(), retrievedEffects.get(mse.getBuffSourceId())));
        }

        List<Pair<BuffStat, Integer>> activeStatups = new LinkedList<>();
        for (Pair<Integer, Pair<StatEffect, Long>> lmse : toUpdateEffects) {
            Pair<StatEffect, Long> msel = lmse.getRight();
            activeStatups.addAll(getActiveStatupsFromSourceid(lmse.getLeft()));
            msel.getLeft().updateBuffEffect(owner, activeStatups, msel.getRight());
            activeStatups.clear();
        }

        List<Pair<Integer, Pair<StatEffect, Long>>> priorityEffects = propagatePriorityBuffEffectUpdates(retrievedStats);
        for (Pair<Integer, Pair<StatEffect, Long>> lmse : priorityEffects) {
            Pair<StatEffect, Long> msel = lmse.getRight();
            activeStatups.addAll(getActiveStatupsFromSourceid(lmse.getLeft()));
            msel.getLeft().updateBuffEffect(owner, activeStatups, msel.getRight());
            activeStatups.clear();
        }

        if (owner.isRidingBattleship()) {
            List<Pair<BuffStat, Integer>> statups = new ArrayList<>(1);
            statups.add(new Pair<>(BuffStat.MONSTER_RIDING, 0));
            owner.sendPacket(PacketCreator.giveBuff(ItemId.BATTLESHIP, 5221006, statups));
            owner.announceBattleshipHp();
        }
    }

    private static BuffStat getSingletonStatupFromEffect(StatEffect mse) {
        for (Pair<BuffStat, Integer> mbs : mse.getStatups()) {
            if (CharacterBuffs.isSingletonStatup(mbs.getLeft())) {
                return mbs.getLeft();
            }
        }

        return null;
    }

    public void registerEffect(StatEffect effect, long starttime, long expirationtime, boolean isSilent) {
        if (effect.isDragonBlood()) {
            owner.prepareDragonBlood(effect);
        } else if (effect.isBerserk()) {
            owner.checkBerserk(owner.isHidden());
        } else if (effect.isBeholder()) {
            final int beholder = DarkKnight.BEHOLDER;
            if (owner.beholderHealingSchedule != null) {
                owner.beholderHealingSchedule.cancel(false);
            }
            if (owner.beholderBuffSchedule != null) {
                owner.beholderBuffSchedule.cancel(false);
            }
            Skill bHealing = SkillFactory.getSkill(DarkKnight.AURA_OF_BEHOLDER);
            int bHealingLvl = owner.getSkillLevel(bHealing);
            if (bHealingLvl > 0) {
                final StatEffect healEffect = bHealing.getEffect(bHealingLvl);
                int healInterval = (int) SECONDS.toMillis(healEffect.getX());
                owner.beholderHealingSchedule = TimerManager.getInstance().register(() -> {
                    if (owner.awayFromWorld.get()) {
                        return;
                    }

                    owner.addHP(healEffect.getHp());
                    owner.sendPacket(PacketCreator.showOwnBuffEffect(beholder, 2));
                    owner.getMap().broadcastMessage(owner, PacketCreator.summonSkill(owner.getId(), beholder, 5), true);
                    owner.getMap().broadcastMessage(owner, PacketCreator.showOwnBuffEffect(beholder, 2), false);
                }, healInterval, healInterval);
            }
            Skill bBuff = SkillFactory.getSkill(DarkKnight.HEX_OF_BEHOLDER);
            if (owner.getSkillLevel(bBuff) > 0) {
                final StatEffect buffEffect = bBuff.getEffect(owner.getSkillLevel(bBuff));
                int buffInterval = (int) SECONDS.toMillis(buffEffect.getX());
                owner.beholderBuffSchedule = TimerManager.getInstance().register(() -> {
                    if (owner.awayFromWorld.get()) {
                        return;
                    }

                    buffEffect.applyTo(owner);
                    owner.sendPacket(PacketCreator.showOwnBuffEffect(beholder, 2));
                    owner.getMap().broadcastMessage(owner, PacketCreator.summonSkill(owner.getId(), beholder, (int) (Math.random() * 3) + 6), true);
                    owner.getMap().broadcastMessage(owner, PacketCreator.showBuffEffect(owner.getId(), beholder, 2), false);
                }, buffInterval, buffInterval);
            }
        } else if (effect.isRecovery()) {
            int healInterval = (GameConfig.getServerBoolean("use_ultra_recovery")) ? 2000 : 5000;
            final byte heal = (byte) effect.getX();

            owner.chrLock.lock();
            try {
                if (owner.recoveryTask != null) {
                    owner.recoveryTask.cancel(false);
                }

                owner.recoveryTask = TimerManager.getInstance().register(() -> {
                    if (getBuffSource(BuffStat.RECOVERY) == -1) {
                        owner.chrLock.lock();
                        try {
                            if (owner.recoveryTask != null) {
                                owner.recoveryTask.cancel(false);
                                owner.recoveryTask = null;
                            }
                        } finally {
                            owner.chrLock.unlock();
                        }

                        return;
                    }

                    owner.addHP(heal);
                    owner.sendPacket(PacketCreator.showOwnRecovery(heal));
                    owner.getMap().broadcastMessage(owner, PacketCreator.showRecovery(owner.id, heal), false);
                }, healInterval, healInterval);
            } finally {
                owner.chrLock.unlock();
            }
        } else if (effect.getHpRRate() > 0 || effect.getMpRRate() > 0) {
            if (effect.getHpRRate() > 0) {
                owner.extraHpRec = effect.getHpR();
                owner.extraRecInterval = effect.getHpRRate();
            }

            if (effect.getMpRRate() > 0) {
                owner.extraMpRec = effect.getMpR();
                owner.extraRecInterval = effect.getMpRRate();
            }

            owner.chrLock.lock();
            try {
                owner.stopExtraTask();
                owner.startExtraTask(owner.extraHpRec, owner.extraMpRec, owner.extraRecInterval);   // HP & MP sharing the same task holder
            } finally {
                owner.chrLock.unlock();
            }

        } else if (effect.isMapChair()) {
            owner.startChairTask();
        }

        owner.prtLock.lock();
        owner.effLock.lock();
        owner.chrLock.lock();
        try {
            Integer sourceid = effect.getBuffSourceId();
            Map<BuffStat, BuffStatValueHolder> toDeploy;
            Map<BuffStat, BuffStatValueHolder> appliedStatups = new LinkedHashMap<>();

            for (Pair<BuffStat, Integer> ps : effect.getStatups()) {
                appliedStatups.put(ps.getLeft(), new BuffStatValueHolder(effect, starttime, ps.getRight()));
            }

            boolean active = effect.isActive(owner);
            if (GameConfig.getServerBoolean("use_buff_most_significant")) {
                toDeploy = new LinkedHashMap<>();
                Map<Integer, Pair<StatEffect, Long>> retrievedEffects = new LinkedHashMap<>();
                Set<BuffStat> retrievedStats = new LinkedHashSet<>();
                for (Entry<BuffStat, BuffStatValueHolder> statup : appliedStatups.entrySet()) {
                    BuffStatValueHolder mbsvh = effects.get(statup.getKey());
                    BuffStatValueHolder statMbsvh = statup.getValue();

                    if (active) {
                        if (mbsvh == null || mbsvh.value < statMbsvh.value || (mbsvh.value == statMbsvh.value && mbsvh.effect.getStatups().size() <= statMbsvh.effect.getStatups().size())) {
                            toDeploy.put(statup.getKey(), statMbsvh);
                        } else {
                            if (!CharacterBuffs.isSingletonStatup(statup.getKey())) {
                                for (Pair<BuffStat, Integer> mbs : mbsvh.effect.getStatups()) {
                                    retrievedStats.add(mbs.getLeft());
                                }
                            }
                        }
                    }

                    addItemEffectHolderCount(statup.getKey());
                }

                // should also propagate update from buffs shared with priority sourceids
                Set<BuffStat> updated = appliedStatups.keySet();
                for (BuffStatValueHolder mbsvh : getAllStatups()) {
                    if (CharacterBuffs.isPriorityBuffSourceId(mbsvh.effect.getBuffSourceId())) {
                        for (Pair<BuffStat, Integer> p : mbsvh.effect.getStatups()) {
                            if (updated.contains(p.getLeft())) {
                                retrievedStats.add(p.getLeft());
                            }
                        }
                    }
                }

                if (!isSilent) {
                    addItemEffectHolder(sourceid, expirationtime, appliedStatups);
                    effects.putAll(toDeploy);

                    if (active) {
                        retrievedEffects.put(sourceid, new Pair<>(effect, starttime));
                    }

                    propagateBuffEffectUpdates(retrievedEffects, retrievedStats, new LinkedHashSet<>());
                }
            } else {
                for (Entry<BuffStat, BuffStatValueHolder> statup : appliedStatups.entrySet()) {
                    addItemEffectHolderCount(statup.getKey());
                }

                toDeploy = (active ? appliedStatups : new LinkedHashMap<>());
            }

            addItemEffectHolder(sourceid, expirationtime, appliedStatups);
            effects.putAll(toDeploy);
        } finally {
            owner.chrLock.unlock();
            owner.effLock.unlock();
            owner.prtLock.unlock();
        }

        owner.updateLocalStats();
    }

    public void silentGiveBuffs(List<Pair<Long, PlayerBuffValueHolder>> buffList) {
        for (Pair<Long, PlayerBuffValueHolder> mbsv : buffList) {
            PlayerBuffValueHolder mbsvh = mbsv.getRight();
            mbsvh.effect.silentApplyBuff(owner, mbsv.getLeft());
        }
    }

    // ── 传播更新（updateActiveEffects 家族，原 Character 平移） ──

    private static StatEffect getEffectFromBuffSource(Map<BuffStat, BuffStatValueHolder> buffSource) {
        try {
            return buffSource.entrySet().iterator().next().getValue().effect;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isUpdatingEffect(Set<StatEffect> activeEffects, StatEffect mse) {
        if (mse == null) {
            return false;
        }

        // thanks xinyifly for noticing "Speed Infusion" crashing game when updating buffs during map transition
        boolean active = mse.isActive(owner);
        if (active) {
            return !activeEffects.contains(mse);
        } else {
            return activeEffects.contains(mse);
        }
    }

    public void updateActiveEffects() {
        owner.effLock.lock();     // thanks davidlafriniere, maple006, RedHat for pointing a deadlock occurring here
        try {
            Set<BuffStat> updatedBuffs = new LinkedHashSet<>();
            Set<StatEffect> activeEffects = new LinkedHashSet<>();

            for (BuffStatValueHolder mse : effects.values()) {
                activeEffects.add(mse.effect);
            }

            for (Map<BuffStat, BuffStatValueHolder> buff : buffEffects.values()) {
                StatEffect mse = getEffectFromBuffSource(buff);
                if (isUpdatingEffect(activeEffects, mse)) {
                    for (Pair<BuffStat, Integer> p : mse.getStatups()) {
                        updatedBuffs.add(p.getLeft());
                    }
                }
            }

            for (BuffStat mbs : updatedBuffs) {
                effects.remove(mbs);
            }

            updateEffects(updatedBuffs);
        } finally {
            owner.effLock.unlock();
        }
    }

    private void updateEffects(Set<BuffStat> removedStats) {
        owner.effLock.lock();
        owner.chrLock.lock();
        try {
            Set<BuffStat> retrievedStats = new LinkedHashSet<>();

            for (BuffStat mbs : removedStats) {
                fetchBestEffectFromItemEffectHolder(mbs);

                BuffStatValueHolder mbsvh = effects.get(mbs);
                if (mbsvh != null) {
                    for (Pair<BuffStat, Integer> statup : mbsvh.effect.getStatups()) {
                        retrievedStats.add(statup.getLeft());
                    }
                }
            }

            propagateBuffEffectUpdates(new LinkedHashMap<Integer, Pair<StatEffect, Long>>(), retrievedStats, removedStats);
        } finally {
            owner.chrLock.unlock();
            owner.effLock.unlock();
        }
    }
}
