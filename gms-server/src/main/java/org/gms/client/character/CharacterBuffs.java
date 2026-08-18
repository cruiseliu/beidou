package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.server.ItemInformationProvider;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
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
import java.util.EnumSet;

import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 最佳效果被取消时从未结束的同槽位 buff 中重选（fetchBest...）。
 *
 * 编排层（registerEffect/cancelEffect 及其伴随调度）留在 Character；
 * debuff/disease 与 visual effect 不在此管理。
 */
class CharacterBuffs {
    private static final Logger log = LoggerFactory.getLogger(CharacterBuffs.class);

    private final Character owner;
    private final CharacterEffects state;

    /** 全部在册 buff（含被压制的同槽位旧 buff），按源分组，key = sourceId */
    final Map<Integer, BuffStatus> entries = new LinkedHashMap<>();

    /** buff 到期定时器（id = sourceid，timestamp = 到期时刻），替代原 1.5s 周期扫描 */
    private final TimeoutHelper expireTimer = new TimeoutHelper();

    /** 冻结时刻，-1 = 未冻结 */
    private long frozenAt = -1;

    CharacterBuffs(Character owner, CharacterEffects state) {
        this.owner = owner;
        this.state = state;
        expireTimer.setListener((sourceid, timestamp) -> {
            cancelBuff(sourceid, false);
        });
    }

    void startExpireTimer() {
        expireTimer.start();
        // 登录补排：静默恢复的 buff 在 start 前入库（schedule 被 TimeoutHelper 丢弃）
        List<BuffStatus> buffs;
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            buffs = new ArrayList<>(entries.values());
        }
        for (BuffStatus buff : buffs) {
            expireTimer.scheduleOrTrigger(buff.sourceId, buff.startTime + buff.duration);
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
            Set<EffectType> toCancel = new LinkedHashSet<>();
            for (EffectType slot : new EffectType[]{EffectType.SUMMON, EffectType.PUPPET}) {
                EffectStatus holder = state.effects.get(slot);
                if (holder != null) {
                    for (Pair<EffectType, Integer> p : holder.getData().getStatups()) {
                        toCancel.add(p.getLeft());
                    }
                }
            }
            if (!toCancel.isEmpty()) {
                List<EffectType> list = new ArrayList<>(toCancel);
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
                for (BuffStatus buff : entries.values()) {
                    // 到期时刻 = startTime + duration，随 startTime 推移自动成立，无需单独维护
                    buff.startTime += skipped;
                }
            }
        }
        startExpireTimer();
    }

    // ── 查询 ──

    /** 按 buffSourceId 去重的在册效果列表 */
    List<BuffEffectData> getAllBuffEffectData() {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            List<BuffEffectData> ret = new ArrayList<>();
            for (BuffStatus buff : entries.values()) {
                ret.add(buff.data);
            }
            return ret;
        }
    }

    boolean containsSourceId(int sourceId) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            return entries.containsKey(sourceId);
        }
    }

    List<EffectStatus> getAllEffects() {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            List<EffectStatus> ret = new ArrayList<>();
            for (BuffStatus buff : entries.values()) {
                ret.addAll(buff.effects);
            }
            return ret;
        }
    }

    /** 已在 effLock & chrLock 内调用 */
    private List<Pair<EffectType, Integer>> getActiveEffectTypesAndValues(int sourceId) {
        List<Pair<EffectType, Integer>> ret = new ArrayList<>();
        List<Pair<EffectType, Integer>> singletonStatups = new ArrayList<>();
        for (EffectStatus effect : entries.get(sourceId).effects) {
            EffectStatus active = state.effects.get(effect.type);

            Pair<EffectType, Integer> p;
            if (active != null) {
                p = new Pair<>(effect.type, active.value);
            } else {
                p = new Pair<>(effect.type, 0);
            }

            if (!isSingletonStatup(effect.type)) {   // thanks resinate, Daddy Egg for pointing out morph issues when updating it along with other statups
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
            Map<EffectType, Long> cachedCounts = new LinkedHashMap<>();
            for (BuffStatus status : entries.values()) {
                for (EffectStatus es : status.effects) {
                    cachedCounts.merge(es.type, 1L, Long::sum);
                }
            }

            log.debug("-------------------");
            log.debug("CACHED BUFF COUNT: {}", cachedCounts.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining(", "))
            );

            log.debug("-------------------");
            log.debug("CACHED BUFFS: {}", entries.values().stream()
                    .map(status -> status.sourceId + ": (" + status.effects.stream()
                            .map(es -> es.type.name() + es.value)
                            .collect(Collectors.joining(", ")) + ")")
                    .collect(Collectors.joining(", "))
            );

            log.debug("-------------------");
            log.debug("IN ACTION: {}", state.effects.entrySet().stream()
                    .map(entry -> entry.getKey().name() + " -> " + ItemInformationProvider.getInstance().getName(entry.getValue().getData().getSourceId()))
                    .collect(Collectors.joining(", "))
            );
        }
    }

    // ── 槽位存储与选择 ──

    private void addBuff(BuffStatus buff) {
        entries.put(buff.sourceId, buff);
        expireTimer.schedule(buff.sourceId, buff.startTime + buff.duration);
    }

    private void removeBuff(int sourceId) {
        entries.remove(sourceId);
        expireTimer.cancel(sourceId);
    }

    /** 同槽位重选最佳：value 最大者优先，同值取 statups 更多者；选中者写入 effects */
    private EffectStatus findBestEffect(EffectType effectType) {
        EffectStatus bestEffect = null;
        for (BuffStatus buff : entries.values()) {
            for (EffectStatus effect : buff.effects) {
                if (effect.type != effectType) {
                    continue;
                }
                if (!effect.getData().isActive(owner)) {
                    continue;
                }

                if (bestEffect == null) {
                    bestEffect = effect;
                } else if (effect.value > bestEffect.value) {
                    bestEffect = effect;
                } else if (effect.value == bestEffect.value && effect.getData().getStatups().size() > bestEffect.getData().getStatups().size()) {
                    bestEffect = effect;
                }
            }
        }

        if (bestEffect != null) {
            state.effects.put(effectType, bestEffect);
        }
        return bestEffect;
    }

    private List<EffectStatus> extractLeastRelevantStatEffectsIfFull(BuffStatus buff) {
        List<EffectStatus> extractedStatBuffs = new ArrayList<>();

        try (var ignored = Locks.acquire(owner.chrLock)) {
            Map<EffectType, Byte> stats = new LinkedHashMap<>();
            Map<EffectType, EffectStatus> minStatBuffs = new LinkedHashMap<>();

            for (BuffStatus b : entries.values()) {
                for (EffectStatus effect : b.effects) {
                    EffectType effectType = effect.type;
                    Byte b2 = stats.get(effectType);

                    if (b2 != null) {
                        stats.put(effectType, (byte) (b2 + 1));
                        if (effect.value < minStatBuffs.get(effectType).value) {
                            minStatBuffs.put(effectType, effect);
                        }
                    } else {
                        stats.put(effectType, (byte) 1);
                        minStatBuffs.put(effectType, effect);
                    }
                }
            }

            Set<EffectType> effectTypes = new LinkedHashSet<>();
            for (Pair<EffectType, Integer> efstat : buff.data.getStatups()) {
                effectTypes.add(efstat.getLeft());
            }

            for (Map.Entry<EffectType, Byte> it : stats.entrySet()) {
                boolean uniqueBuff = isSingletonStatup(it.getKey());

                if (it.getValue() >= (!uniqueBuff ? GameConfig.getServerByte("max_monitored_buff_stats") : 1) && effectTypes.contains(it.getKey())) {
                    EffectStatus effect = minStatBuffs.get(it.getKey());

                    BuffStatus target = effect.buff;
                    target.effects.removeIf(es -> es.type == it.getKey());

                    if (target.effects.isEmpty()) {
                        entries.remove(effect.buff.sourceId);
                    }
                    extractedStatBuffs.add(effect);
                }
            }
        }

        return extractedStatBuffs;
    }

    // ── 全量取消 ──

    void cancelAllBuffs(boolean softcancel) {
        if (softcancel) {
            try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
                cancelEffectFromBuffStat(EffectType.SUMMON);
                cancelEffectFromBuffStat(EffectType.PUPPET);

                state.effects.clear();

                for (Integer srcid : new ArrayList<>(entries.keySet())) {
                    removeBuff(srcid);
                }
            }
        } else {
            List<Integer> sourceIds;
            try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
                sourceIds = new ArrayList<>(entries.keySet());
            }
            for (Integer sourceId : sourceIds) {
                cancelBuff(sourceId, false);
            }
        }
    }

    // ── 静态判定 ──

    private static boolean isSingletonStatup(EffectType effectType) {
        return switch (effectType) {           //HPREC and MPREC are supposed to be singleton
            case COUPON_EXP1, COUPON_EXP2, COUPON_EXP3, COUPON_EXP4, COUPON_DRP1, COUPON_DRP2, COUPON_DRP3,
                 MESO_UP_BY_ITEM,
                 ITEM_UP_BY_ITEM, RESPECT_PIMMUNE, RESPECT_MIMMUNE, DEFENSE_ATT, DEFENSE_STATE, WATK, WDEF, MATK, MDEF,
                 ACC, AVOID, SPEED, JUMP -> false;
            default -> true;
        };
    }

    private static boolean isPriorityBuffSourceId(int sourceId) {
        return -ItemId.ROSE_SCENT == sourceId || -ItemId.FREESIA_SCENT == sourceId || -ItemId.LAVENDER_SCENT == sourceId;
    }

    // ── 编排层：注册/取消/传播（原 Character 平移，Character.this → owner） ──

    private void cancelPlayerBuffs(List<EffectType> effectTypes) {
        if (owner.client.getChannelServer().getPlayerStorage().getCharacterById(owner.getId()) != null) {
            owner.updateLocalStats();
            owner.sendPacket(PacketCreator.cancelBuff(effectTypes));
            if (!effectTypes.isEmpty()) {
                owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignBuff(owner.getId(), effectTypes), false);
            }
        }
    }

    /** 簿记摘除：源列表移除对应槽位；若激活表上是同源 holder 则一并移出并收集（供 publish 做伴随清理） */
    private List<EffectStatus> deregisterBuffStats(List<EffectStatus> effects) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            List<EffectStatus> deactivated = new ArrayList<>(effects.size());
            for (EffectStatus effect : effects) {
                int sourceId = effect.getData().getBuffSourceId();

                EffectStatus active = state.effects.get(effect.type);
                if (active != null && active.getData().getBuffSourceId() == sourceId) {
                    state.effects.remove(effect.type);
                    deactivated.add(active);
                }
            }

            return deactivated;
        }
    }

    boolean cancelBuff(int sourceId, boolean overwrite) {
        boolean isMagicDoor = false;
        boolean ret = false;

        try (var ignored = Locks.acquire(owner.prtLock, owner.effLock)) {
            BuffStatus buff = entries.get(sourceId);
            if (buff == null) {
                return false;
            }
            isMagicDoor = buff.data.isMagicDoor();

            Set<EffectType> removedStats = new LinkedHashSet<>();
            EffectChangeReport report = computeCancellation(buff, overwrite, removedStats);
            owner.updateLocalStats();
            publish(report);

            ret = !removedStats.isEmpty();
        }

        if (isMagicDoor && ret) {
            try (var ignored = Locks.acquire(owner.prtLock, owner.effLock)) {
                if (!containsSourceId(Priest.MYSTIC_DOOR)) {
                    Door.attemptRemoveDoor(owner);
                }
            }
        }

        return ret;
    }

    /** 步骤1：选择摘除集合并应用（仅内部状态变更），产出对外变化清单 */
    private EffectChangeReport computeCancellation(BuffStatus buff, boolean overwrite, Set<EffectType> removedStats) {
        EffectChangeReport report = new EffectChangeReport();
        List<EffectStatus> buffstats = null;
        EffectType effectType;
        if (!overwrite) {   // is removing the source effect, meaning every effect from this srcid is being purged
            try (var ignored = Locks.acquire(owner.chrLock)) {
                BuffStatus removed = entries.remove(buff.sourceId);
                buffstats = removed != null ? removed.effects : new ArrayList<>();
            }

        } else if ((effectType = getSingletonStatupFromEffect(buff)) != null) {   // removing all effects of a buff having non-shareable buff stat.
            if (state.effects.get(effectType) != null) {
                try (var ignored = Locks.acquire(owner.chrLock)) {
                    BuffStatus removed = entries.remove(buff.sourceId);
                    buffstats = removed != null ? removed.effects : new ArrayList<>();
                }
            }
        }

        if (buffstats == null) {            // all else, is dropping ALL current statups that uses same stats as the given effect
            buffstats = extractLeastRelevantStatEffectsIfFull(buff);
        }

        if (buff.data.isMapChair()) {
            report.stopChairTask = true;
        }

        report.deactivated.addAll(deregisterBuffStats(buffstats));
        if (buff.data.isMonsterRiding()) {
            report.unregisterMountHunger = true;
        }

        if (!overwrite) {
            for (EffectStatus es : buffstats) {
                removedStats.add(es.type);
            }
        }

        fillReselectAndReannounce(report, removedStats);
        return report;
    }

    void cancelEffectFromBuffStat(EffectType effectType) {
        EffectStatus effect;

        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            effect = state.effects.get(effectType);
        }
        if (effect != null) {
            cancelBuff(effect.buff.sourceId, false);
        }
    }

    void cancelBuffStats(EffectType effectType) {
        try (var ignored = Locks.acquire(owner.effLock)) {
            List<Pair<Integer, EffectStatus>> cancelList = new LinkedList<>();

            try (var ignored2 = Locks.acquire(owner.chrLock)) {
                for (BuffStatus buff : entries.values()) {
                    for (EffectStatus effect : buff.effects) {
                        if (effect.type == effectType) {
                            cancelList.add(new Pair<>(buff.sourceId, effect));
                        }
                    }
                }
            }

            EffectChangeReport report = new EffectChangeReport();
            for (Pair<Integer, EffectStatus> p : cancelList) {
                int sourceId = p.getLeft();
                EffectStatus effect = p.getRight();

                try (var ignored3 = Locks.acquire(owner.chrLock)) {
                    BuffStatus buff = entries.get(sourceId);
                    if (buff == null) {
                        return;
                    }

                    if (buff.effects.removeIf(eff -> eff.type == effectType)) {
                        if (buff.effects.isEmpty()) {
                            entries.remove(sourceId);
                            expireTimer.cancel(sourceId);
                        }
                    }
                }

                report.deactivated.addAll(deregisterBuffStats(Collections.singletonList(effect)));
            }
            findBestEffect(effectType);   // 同槽重选（原 dropBuffStats 职责）
            publish(report);
        }

        cancelPlayerBuffs(Collections.singletonList(effectType));
    }


    /**
     * 传播更新的发送序（弱→强，最强者的包最后落地）。
     * 偏序约束：两效果共享某槽位且前者槽位值更低（更弱、被后者压制）→ 前者先发。
     */
    private static List<BuffEffectData> sortEffectsList(Map<BuffEffectData, Integer> updateEffectsList) {
        Map<BuffEffectData, Map<EffectType, Integer>> effectSlots = new LinkedHashMap<>();
        for (BuffEffectData data : updateEffectsList.keySet()) {
            Map<EffectType, Integer> slots = new LinkedHashMap<>();
            for (Pair<EffectType, Integer> statup : data.getStatups()) {
                slots.put(statup.getLeft(), statup.getRight());
            }
            effectSlots.put(data, slots);
        }

        return TopologicalSorter.sort(effectSlots.keySet(), (a, b) -> {
            for (Entry<EffectType, Integer> ea : effectSlots.get(a).entrySet()) {
                Integer vb = effectSlots.get(b).get(ea.getKey());
                if (vb != null && ea.getValue() < vb) {
                    return true;
                }
            }
            return false;
        });
    }


    /** 一次注册/取消在步骤1（选择+应用，仅改 CharacterBuffs/CharacterEffects 内部状态）产出的对外变化清单 */
    private static class EffectChangeReport {
        /** 从激活表移除的持有者（驱动 CANCEL_BUFF 与 RECOVERY/召唤物/龙血/HPREC 伴随清理） */
        final List<EffectStatus> deactivated = new ArrayList<>();
        /** 已无激活者的槽位，需向客户端通告取消 */
        final Set<EffectType> lostSlots = new LinkedHashSet<>();
        /** 需重发 updateBuffEffect 的（效果, 基准时刻），拓扑序在前、priority 源在后 */
        final List<Pair<BuffEffectData, Long>> reannounced = new ArrayList<>();
        /** 传播实际发生（retrievedStats 非空）时需重发骑船显示 */
        boolean battleshipRefresh = false;
        boolean stopChairTask = false;
        boolean unregisterMountHunger = false;
    }

    /** 步骤2：按变化清单推送对外副作用（发包/召唤物清理/伴随任务停启）。调用方持锁。 */
    private void publish(EffectChangeReport report) {
        for (EffectStatus es : report.deactivated) {
            if (es.type == EffectType.RECOVERY) {
                if (owner.recoveryTask != null) {
                    owner.recoveryTask.cancel(false);
                    owner.recoveryTask = null;
                }
            } else if (es.type == EffectType.SUMMON || es.type == EffectType.PUPPET) {
                Summon summon = owner.summons.get(es.getData().getSourceId());
                if (summon != null) {
                    owner.removeSummonAndPuppet(summon);
                }
            } else if (es.type == EffectType.DRAGONBLOOD) {
                owner.dragonBloodSchedule.cancel(false);
                owner.dragonBloodSchedule = null;
            } else if (es.type == EffectType.HPREC || es.type == EffectType.MPREC) {
                if (es.type == EffectType.HPREC) {
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
        if (report.stopChairTask) {
            owner.stopChairTask();
        }
        if (report.unregisterMountHunger) {
            owner.getClient().getWorldServer().unregisterMountHunger(owner);
            owner.getMapleMount().setActive(false);
        }
        if (!report.lostSlots.isEmpty()) {
            cancelPlayerBuffs(new ArrayList<>(report.lostSlots));
        }
        for (Pair<BuffEffectData, Long> mse : report.reannounced) {
            mse.getLeft().updateBuffEffect(owner, getActiveEffectTypesAndValues(mse.getLeft().getBuffSourceId()), mse.getRight());
        }
        if (report.battleshipRefresh && owner.isRidingBattleship()) {
            List<Pair<EffectType, Integer>> statups = new ArrayList<>(1);
            statups.add(new Pair<>(EffectType.MONSTER_RIDING, 0));
            owner.sendPacket(PacketCreator.giveBuff(ItemId.BATTLESHIP, 5221006, statups));
            owner.announceBattleshipHp();
        }
    }

    /** 传播计算（纯读变更后状态）：填 report 的 lostSlots/reannounced/battleshipRefresh */
    private void computeReannounce(EffectChangeReport report, Map<Integer, Pair<BuffEffectData, Long>> retrievedEffects, Set<EffectType> retrievedStats, Set<EffectType> removedStats) {
        for (EffectType mbs : removedStats) {
            if (!retrievedStats.contains(mbs)) {
                report.lostSlots.add(mbs);
            }
        }
        if (retrievedStats.isEmpty()) {
            return;
        }
        report.battleshipRefresh = true;

        Map<EffectType, Pair<Integer, BuffEffectData>> maxBuffValue = new LinkedHashMap<>();
        for (EffectType effectType : retrievedStats) {
            EffectStatus effect = state.effects.get(effectType);
            if (effect != null) {
                retrievedEffects.put(effect.getData().getBuffSourceId(), new Pair<>(effect.getData(), effect.buff.startTime));
            }

            maxBuffValue.put(effectType, new Pair<>(Integer.MIN_VALUE, null));
        }

        Map<BuffEffectData, Integer> updateEffects = new LinkedHashMap<>();

        List<BuffEffectData> recalcMseList = new LinkedList<>();
        for (Entry<Integer, Pair<BuffEffectData, Long>> re : retrievedEffects.entrySet()) {
            recalcMseList.add(re.getValue().getLeft());
        }

        boolean mageJob = owner.getJobStyle() == Job.MAGICIAN;
        do {
            List<BuffEffectData> mseList = recalcMseList;
            recalcMseList = new LinkedList<>();

            for (BuffEffectData mse : mseList) {
                int maxEffectiveStatup = Integer.MIN_VALUE;
                for (Pair<EffectType, Integer> st : mse.getStatups()) {
                    EffectType mbs = st.getLeft();

                    boolean relevantStatup = true;
                    if (mbs == EffectType.WATK) {  // not relevant for mages
                        if (mageJob) {
                            relevantStatup = false;
                        }
                    } else if (mbs == EffectType.MATK) { // not relevant for non-mages
                        if (!mageJob) {
                            relevantStatup = false;
                        }
                    }

                    Pair<Integer, BuffEffectData> mbv = maxBuffValue.get(mbs);
                    if (mbv == null) {
                        continue;
                    }

                    if (mbv.getLeft() < st.getRight()) {
                        BuffEffectData msbe = mbv.getRight();
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

        List<BuffEffectData> updateEffectsList = sortEffectsList(updateEffects);
        for (BuffEffectData mse : updateEffectsList) {
            report.reannounced.add(retrievedEffects.get(mse.getBuffSourceId()));
        }

        for (Pair<Integer, Pair<BuffEffectData, Long>> lmse : propagatePriorityBuffEffectUpdates(retrievedStats)) {
            report.reannounced.add(lmse.getRight());
        }
    }

    /** 重选+传播（取消链的公共尾部）：对每个受影响槽重选最佳，再计算重发集填入 report */
    private void fillReselectAndReannounce(EffectChangeReport report, Set<EffectType> removedTypes) {
        Set<EffectType> retrievedStats = new LinkedHashSet<>();

        for (EffectType effectType : removedTypes) {
            findBestEffect(effectType);

            EffectStatus effect = state.effects.get(effectType);
            if (effect != null) {
                for (Pair<EffectType, Integer> statup : effect.getData().getStatups()) {
                    retrievedStats.add(statup.getLeft());
                }
            }
        }

        computeReannounce(report, new LinkedHashMap<>(), retrievedStats, removedTypes);
    }

    private List<Pair<Integer, Pair<BuffEffectData, Long>>> propagatePriorityBuffEffectUpdates(Set<EffectType> retrievedStats) {
        List<Pair<Integer, Pair<BuffEffectData, Long>>> priorityUpdateEffects = new LinkedList<>();
        Map<EffectStatus, BuffEffectData> yokeStats = new LinkedHashMap<>();

        // priority buffsources: override buffstats for the client to perceive those as "currently buffed"
        Set<EffectStatus> mbsvhList = new LinkedHashSet<>(getAllEffects());

        for (EffectStatus mbsvh : mbsvhList) {
            BuffEffectData mse = mbsvh.getData();
            int buffSourceId = mse.getBuffSourceId();
            if (CharacterBuffs.isPriorityBuffSourceId(buffSourceId) && !state.hasActiveBuff(buffSourceId)) {
                for (Pair<EffectType, Integer> ps : mse.getStatups()) {
                    EffectType mbs = ps.getLeft();
                    if (retrievedStats.contains(mbs)) {
                        EffectStatus mbsvhe = state.effects.get(mbs);

                        // this shouldn't even be null...
                        //if (mbsvh != null) {
                        yokeStats.put(mbsvh, mbsvhe.getData());
                        //}
                    }
                }
            }
        }

        for (Entry<EffectStatus, BuffEffectData> e : yokeStats.entrySet()) {
            EffectStatus mbsvhPriority = e.getKey();
            BuffEffectData mseActive = e.getValue();

            priorityUpdateEffects.add(new Pair<>(mseActive.getBuffSourceId(), new Pair<>(mbsvhPriority.getData(), mbsvhPriority.buff.startTime)));
        }

        return priorityUpdateEffects;
    }


    private static EffectType getSingletonStatupFromEffect(BuffStatus buff) {
        for (Pair<EffectType, Integer> mbs : buff.data.getStatups()) {
            if (CharacterBuffs.isSingletonStatup(mbs.getLeft())) {
                return mbs.getLeft();
            }
        }

        return null;
    }

    void registerEffect(BuffEffectData effect, long starttime, long expirationtime, boolean isSilent) {
        startCompanionTasks(effect);

        EffectChangeReport report = null;
        try (var ignored = Locks.acquire(owner.prtLock, owner.effLock, owner.chrLock)) {
            report = computeRegistration(effect, starttime, expirationtime, isSilent);
            if (report != null) {
                publish(report);
            }
        }

        owner.updateLocalStats();
    }

    /** 伴随任务启动（龙血/狂暴/小灵/恢复跳/额外回复/椅子），锁外先行 */
    private void startCompanionTasks(BuffEffectData effect) {
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
                final BuffEffectData healEffect = bHealing.getEffect(bHealingLvl);
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
                final BuffEffectData buffEffect = bBuff.getEffect(owner.getSkillLevel(bBuff));
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

            try (var ignored = Locks.acquire(owner.chrLock)) {
                if (owner.recoveryTask != null) {
                    owner.recoveryTask.cancel(false);
                }

                owner.recoveryTask = TimerManager.getInstance().register(() -> {
                    if (state.getBuffSource(EffectType.RECOVERY) == -1) {
                        try (var ignored2 = Locks.acquire(owner.chrLock)) {
                            if (owner.recoveryTask != null) {
                                owner.recoveryTask.cancel(false);
                                owner.recoveryTask = null;
                            }
                        }

                        return;
                    }

                    owner.addHP(heal);
                    owner.sendPacket(PacketCreator.showOwnRecovery(heal));
                    owner.getMap().broadcastMessage(owner, PacketCreator.showRecovery(owner.id, heal), false);
                }, healInterval, healInterval);
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

            try (var ignored = Locks.acquire(owner.chrLock)) {
                owner.stopExtraTask();
                owner.startExtraTask(owner.extraHpRec, owner.extraMpRec, owner.extraRecInterval);   // HP & MP sharing the same task holder
            }

        } else if (effect.isMapChair()) {
            owner.startChairTask();
        }
    }

    /**
     * 步骤1：簿记入册 + 激活表部署（仅内部状态变更）。
     *
     * @return 需要推送的变化清单；isSilent 时返回 null（静默恢复不发包）
     */
    private EffectChangeReport computeRegistration(BuffEffectData effect, long starttime, long expirationtime, boolean isSilent) {
        Integer sourceid = effect.getBuffSourceId();
        BuffStatus buff = new BuffStatus(sourceid, effect, starttime, expirationtime - starttime);
        List<EffectStatus> appliedStatups = new ArrayList<>(effect.getStatups().size());

        // wz 原始 statups 无序；同源同槽重复属数据异常，去重并告警
        Set<EffectType> seenTypes = EnumSet.noneOf(EffectType.class);
        for (Pair<EffectType, Integer> ps : effect.getStatups()) {
            if (!seenTypes.add(ps.getLeft())) {
                log.warn("效果 {} 的 statups 存在重复槽位 {}，已忽略重复项", sourceid, ps.getLeft());
                continue;
            }
            appliedStatups.add(new EffectStatus(ps.getLeft(), buff, ps.getRight()));
        }
        buff.effects.addAll(appliedStatups);

        boolean active = effect.isActive(owner);
        if (GameConfig.getServerBoolean("use_buff_most_significant")) {
            List<EffectStatus> toDeploy = new ArrayList<>(appliedStatups.size());
            Set<EffectType> retrievedStats = new LinkedHashSet<>();
            for (EffectStatus statMbsvh : appliedStatups) {
                EffectStatus incumbent = state.effects.get(statMbsvh.type);

                if (active) {
                    if (incumbent == null || incumbent.value < statMbsvh.value || (incumbent.value == statMbsvh.value && incumbent.getData().getStatups().size() <= statMbsvh.getData().getStatups().size())) {
                        toDeploy.add(statMbsvh);
                    } else {
                        if (!isSingletonStatup(statMbsvh.type)) {
                            for (Pair<EffectType, Integer> mbs : incumbent.getData().getStatups()) {
                                retrievedStats.add(mbs.getLeft());
                            }
                        }
                    }
                }
            }

            // should also propagate update from buffs shared with priority sourceids
            Set<EffectType> updated = seenTypes;
            for (EffectStatus mbsvh : getAllEffects()) {
                if (CharacterBuffs.isPriorityBuffSourceId(mbsvh.getData().getBuffSourceId())) {
                    for (Pair<EffectType, Integer> p : mbsvh.getData().getStatups()) {
                        if (updated.contains(p.getLeft())) {
                            retrievedStats.add(p.getLeft());
                        }
                    }
                }
            }

            addBuff(buff);
            deployToEffects(toDeploy);

            if (isSilent) {
                return null;
            }
            Map<Integer, Pair<BuffEffectData, Long>> retrievedEffects = new LinkedHashMap<>();
            if (active) {
                retrievedEffects.put(sourceid, new Pair<>(effect, starttime));
            }
            EffectChangeReport report = new EffectChangeReport();
            computeReannounce(report, retrievedEffects, retrievedStats, new LinkedHashSet<>());
            return report;
        } else {
            addBuff(buff);
            deployToEffects(active ? appliedStatups : new ArrayList<>());
            return null;
        }
    }

    /** 部署到状态表（按 holder 自带槽位逐项写入激活表） */
    private void deployToEffects(List<EffectStatus> toDeploy) {
        for (EffectStatus es : toDeploy) {
            state.effects.put(es.type, es);
        }
    }

    // ── 传播更新（updateActiveEffects 家族，原 Character 平移） ──

    private boolean isUpdatingEffect(Set<BuffEffectData> activeEffects, BuffEffectData mse) {
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

    void updateActiveEffects() {
        try (var ignored = Locks.acquire(owner.effLock)) {     // thanks davidlafriniere, maple006, RedHat for pointing a deadlock occurring here
            Set<EffectType> updatedBuffs = new LinkedHashSet<>();
            Set<BuffEffectData> activeEffects = new LinkedHashSet<>();

            for (EffectStatus mse : state.effects.values()) {
                activeEffects.add(mse.getData());
            }

            for (BuffStatus buff : entries.values()) {
                BuffEffectData data = buff.data;
                if (isUpdatingEffect(activeEffects, data)) {
                    for (Pair<EffectType, Integer> p : data.getStatups()) {
                        updatedBuffs.add(p.getLeft());
                    }
                }
            }

            for (EffectType mbs : updatedBuffs) {
                state.effects.remove(mbs);
            }

            EffectChangeReport report = new EffectChangeReport();
            try (var ignored2 = Locks.acquire(owner.chrLock)) {
                fillReselectAndReannounce(report, updatedBuffs);
            }
            publish(report);
        }
    }
}
