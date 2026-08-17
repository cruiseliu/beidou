package org.gms.client.character;

import org.gms.client.BuffStat;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.Job;
import org.gms.config.GameConfig;
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
import org.gms.util.TopologicalSorter;
import org.gms.util.TimeoutHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
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
    private final CharacterEffects state;

    /** 全部在册效果（含被压制的同槽位旧 buff），key = buffSourceId */
    final Map<Integer, Map<BuffStat, BuffStatValueHolder>> buffEffects = new LinkedHashMap<>();
    /** 每源到期时刻 */
    final Map<Integer, Long> buffExpires = new LinkedHashMap<>();

    /** buff 到期定时器（id = sourceid，timestamp = 到期时刻），替代原 1.5s 周期扫描 */
    private final TimeoutHelper expireTimer = new TimeoutHelper();

    /** 冻结时刻，-1 = 未冻结 */
    private long frozenAt = -1;

    CharacterBuffs(Character owner, CharacterEffects state) {
        this.owner = owner;
        this.state = state;
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

    /**
     * 冻结全部 buff 计时（离开游戏世界：换频道/进商城/进MTS）。
     * 簿记保留在本对象上（对象在过渡期间存活于 world storage），仅停表并记录冻结时刻。
     *
     * @param announceCancel 对齐旧版取消链的客户端表现：发 CANCEL_BUFF/cancelForeignBuff
     *                       通知召唤类槽位"取消"（假取消——服务端不移除，重入时恢复）
     */
    void freeze(boolean announceCancel) {
        frozenAt = Server.getInstance().getCurrentTime();
        stopExpireTimer();

        if (announceCancel) {
            Set<BuffStat> toCancel = new LinkedHashSet<>();
            for (BuffStat slot : new BuffStat[]{BuffStat.SUMMON, BuffStat.PUPPET}) {
                BuffStatValueHolder holder = state.effects.get(slot);
                if (holder != null) {
                    for (Pair<BuffStat, Integer> p : holder.effect.getStatups()) {
                        toCancel.add(p.getLeft());
                    }
                }
            }
            if (!toCancel.isEmpty()) {
                List<BuffStat> list = new ArrayList<>(toCancel);
                owner.sendPacket(PacketCreator.cancelBuff(list));
                owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignBuff(owner.getId(), list), false);
            }
        }
    }

    boolean isFrozen() {
        return frozenAt >= 0;
    }

    /** 恢复 buff 计时：推移冻结期间跳过的时间并重启到期定时器。未冻结时空操作。 */
    void resume() {
        if (frozenAt < 0) {
            return;
        }
        long skipped = Server.getInstance().getCurrentTime() - frozenAt;
        frozenAt = -1;
        if (skipped > 0) {
            try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
                for (Map<BuffStat, BuffStatValueHolder> bel : buffEffects.values()) {
                    for (BuffStatValueHolder holder : bel.values()) {
                        holder.startTime += skipped;
                    }
                }
                buffExpires.replaceAll((srcid, expirationtime) -> expirationtime + skipped);
            }
        }
        startExpireTimer();
    }

    // ── 查询 ──

    /** 按 buffSourceId 去重的在册效果列表 */
    List<StatEffect> getAllBuffs() {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            Map<Integer, StatEffect> ret = new LinkedHashMap<>();
            for (Map<BuffStat, BuffStatValueHolder> bel : buffEffects.values()) {
                for (BuffStatValueHolder mbsvh : bel.values()) {
                    int srcid = mbsvh.effect.getBuffSourceId();
                    ret.putIfAbsent(srcid, mbsvh.effect);
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
            BuffStatValueHolder mbsvh = state.effects.get(bel.getKey());

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
            Map<BuffStat, Long> cachedCounts = new LinkedHashMap<>();
            for (Map<BuffStat, BuffStatValueHolder> bel : buffEffects.values()) {
                for (BuffStat stat : bel.keySet()) {
                    cachedCounts.merge(stat, 1L, Long::sum);
                }
            }

            log.debug("-------------------");
            log.debug("CACHED BUFF COUNT: {}", cachedCounts.entrySet().stream()
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
            log.debug("IN ACTION: {}", state.effects.entrySet().stream()
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
        buffEffects.remove(sourceid);
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
            state.effects.put(mbs, mbsvh);
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
                stats.putAll(buffList);
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

                    if (lpbe.isEmpty()) {
                        buffEffects.remove(mbsvh.effect.getBuffSourceId());
                    }
                    extractedStatBuffs.put(it.getKey(), mbsvh);
                }
            }
        }

        return extractedStatBuffs;
    }

    // ── 全量取消 ──

    void cancelAllBuffs(boolean softcancel) {
        if (softcancel) {
            try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
                owner.cancelEffectFromBuffStat(BuffStat.SUMMON);
                owner.cancelEffectFromBuffStat(BuffStat.PUPPET);

                state.effects.clear();

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

                BuffStatValueHolder mbsvh = state.effects.get(mbs);
                if (mbsvh != null && mbsvh.effect.getBuffSourceId() == sourceid) {
                    mbsvh.bestApplied = true;
                    state.effects.remove(mbs);

                    if (mbs == BuffStat.RECOVERY) {
                        if (owner.recoveryTask != null) {
                            owner.recoveryTask.cancel(false);
                            owner.recoveryTask = null;
                        }
                    } else if (mbs == BuffStat.SUMMON || mbs == BuffStat.PUPPET) {
                        Summon summon = owner.summons.get(mbsvh.effect.getSourceId());
                        if (summon != null) {
                            owner.removeSummonAndPuppet(summon);
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
            BuffStatValueHolder mbsvh = state.effects.get(ombs);
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
            effect = state.effects.get(stat);
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

    /**
     * 传播更新的发送序（弱→强，最强者的包最后落地）。
     * 偏序约束：两效果共享某槽位且前者槽位值更低（更弱、被后者压制）→ 前者先发。
     */
    private static List<StatEffect> sortEffectsList(Map<StatEffect, Integer> updateEffectsList) {
        Map<StatEffect, Map<BuffStat, Integer>> effectSlots = new LinkedHashMap<>();
        for (StatEffect mse : updateEffectsList.keySet()) {
            Map<BuffStat, Integer> slots = new LinkedHashMap<>();
            for (Pair<BuffStat, Integer> statup : mse.getStatups()) {
                slots.put(statup.getLeft(), statup.getRight());
            }
            effectSlots.put(mse, slots);
        }

        return TopologicalSorter.sort(effectSlots.keySet(), (a, b) -> {
            for (Entry<BuffStat, Integer> ea : effectSlots.get(a).entrySet()) {
                Integer vb = effectSlots.get(b).get(ea.getKey());
                if (vb != null && ea.getValue() < vb) {
                    return true;
                }
            }
            return false;
        });
    }

    private List<Pair<Integer, Pair<StatEffect, Long>>> propagatePriorityBuffEffectUpdates(Set<BuffStat> retrievedStats) {
        List<Pair<Integer, Pair<StatEffect, Long>>> priorityUpdateEffects = new LinkedList<>();
        Map<BuffStatValueHolder, StatEffect> yokeStats = new LinkedHashMap<>();

        // priority buffsources: override buffstats for the client to perceive those as "currently buffed"
        Set<BuffStatValueHolder> mbsvhList = new LinkedHashSet<>(getAllStatups());

        for (BuffStatValueHolder mbsvh : mbsvhList) {
            StatEffect mse = mbsvh.effect;
            int buffSourceId = mse.getBuffSourceId();
            if (CharacterBuffs.isPriorityBuffSourceId(buffSourceId) && !state.hasActiveBuff(buffSourceId)) {
                for (Pair<BuffStat, Integer> ps : mse.getStatups()) {
                    BuffStat mbs = ps.getLeft();
                    if (retrievedStats.contains(mbs)) {
                        BuffStatValueHolder mbsvhe = state.effects.get(mbs);

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
            BuffStatValueHolder mbsvh = state.effects.get(mbs);
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
                    if (state.getBuffSource(BuffStat.RECOVERY) == -1) {
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
                    BuffStatValueHolder mbsvh = state.effects.get(statup.getKey());
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
                    state.effects.putAll(toDeploy);

                    if (active) {
                        retrievedEffects.put(sourceid, new Pair<>(effect, starttime));
                    }

                    propagateBuffEffectUpdates(retrievedEffects, retrievedStats, new LinkedHashSet<>());
                }
            } else {
                toDeploy = (active ? appliedStatups : new LinkedHashMap<>());
            }

            addItemEffectHolder(sourceid, expirationtime, appliedStatups);
            state.effects.putAll(toDeploy);
        } finally {
            owner.chrLock.unlock();
            owner.effLock.unlock();
            owner.prtLock.unlock();
        }

        owner.updateLocalStats();
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

            for (BuffStatValueHolder mse : state.effects.values()) {
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
                state.effects.remove(mbs);
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

                BuffStatValueHolder mbsvh = state.effects.get(mbs);
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
