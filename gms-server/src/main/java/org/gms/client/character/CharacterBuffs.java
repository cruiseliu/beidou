package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.net.server.Server;
import org.gms.server.ItemInformationProvider;
import org.gms.server.BuffEffectData;
import org.gms.util.PacketCreator;
import org.gms.constants.skills.Priest;
import org.gms.server.maps.Door;
import org.gms.util.Locks;
import org.gms.util.Pair;
import org.gms.util.TimeoutHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * buff 机制层（L1 生命周期 + 跨层编排）：在册簿记、到期定时、冻结/恢复、按源查询，
 * 以及注册/取消/活性重算的管线驱动——调 {@link BuffSelector}（L2 纯选择）拿
 * {@link EffectChangeReport}，应用 L1 变更（applyRemovals）并委托 {@link ActiveBuffs}（L3）
 * 应用激活表变更、发布对外副作用与启动伴随任务。
 *
 * 三层职责：
 * - L1 本类：entries 增删、expireTimer、freeze/resume、按源查询（不看槽位竞争、不发包）；
 * - L2 BuffSelector：覆盖选择（同槽最佳/压制/驱逐/重选/传播），纯计算零副作用；
 * - L3 ActiveBuffs：激活表存储/查询/变更应用、发包与伴随任务启停。
 */
class CharacterBuffs {
    private static final Logger log = LoggerFactory.getLogger(CharacterBuffs.class);

    private final Character owner;
    private final ActiveBuffs active;

    /** buff 子系统事务锁（原 Character.effLock，职责收缩后随子系统内聚到此）：
     *  串行化 entries 写者 / 快照发布 / L2→L3 事务链 / freeze/resume/定时器 */
    private final Lock lock = new ReentrantLock(true);

    /** 全部在册 buff（含被压制的同槽位旧 buff），按源分组，key = sourceId */
    final Map<Integer, BuffStatus> entries = new LinkedHashMap<>();

    /** buff 到期定时器（id = sourceid，timestamp = 到期时刻），替代原 1.5s 周期扫描 */
    private final TimeoutHelper expireTimer = new TimeoutHelper();

    /** 冻结时刻，-1 = 未冻结 */
    private long frozenAt = -1;

    CharacterBuffs(Character owner) {
        this.owner = owner;
        this.active = new ActiveBuffs(owner);
        expireTimer.setListener((sourceid, timestamp) -> {
            cancelBuff(sourceid, false);
        });
    }

    /** L3 激活处理层访问器（Character 门面查询激活 buff 状态用） */
    ActiveBuffs getActive() {
        return active;
    }

    void startExpireTimer() {
        expireTimer.start();
        // 重入补排：冻结期间存活于在册表的 buff 重新挂到期定时（换频道/商城退出时 resume 调用本方法）
        List<BuffStatus> buffs;
        try (var ignored = Locks.acquire(lock, owner.chrLock)) {
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
                EffectStatus holder = active.effects.get(slot);
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
            try (var ignored = Locks.acquire(lock, owner.chrLock)) {
                for (BuffStatus buff : entries.values()) {
                    // 到期时刻 = startTime + duration，随 startTime 推移自动成立，无需单独维护
                    buff.startTime += skipped;
                }
            }
        }
        startExpireTimer();
    }

    // ── 查询（L1：按源，不看槽位竞争） ──

    /** 按 buffSourceId 去重的在册效果列表 */
    List<BuffEffectData> getAllBuffEffectData() {
        try (var ignored = Locks.acquire(lock, owner.chrLock)) {
            List<BuffEffectData> ret = new ArrayList<>();
            for (BuffStatus buff : entries.values()) {
                ret.add(buff.data);
            }
            return ret;
        }
    }

    boolean containsSourceId(int sourceId) {
        try (var ignored = Locks.acquire(lock, owner.chrLock)) {
            return entries.containsKey(sourceId);
        }
    }

    List<EffectStatus> getAllEffects() {
        try (var ignored = Locks.acquire(lock, owner.chrLock)) {
            List<EffectStatus> ret = new ArrayList<>();
            for (BuffStatus buff : entries.values()) {
                ret.addAll(buff.effects);
            }
            return ret;
        }
    }

    void debugListAllBuffs() {
        try (var ignored = Locks.acquire(lock, owner.chrLock)) {
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
            log.debug("IN ACTION: {}", active.effects.entrySet().stream()
                    .map(entry -> entry.getKey().name() + " -> " + ItemInformationProvider.getInstance().getName(entry.getValue().getData().getSourceId()))
                    .collect(Collectors.joining(", "))
            );
        }
    }

    // ── L1 生命周期变更 ──

    private void addBuff(BuffStatus buff) {
        entries.put(buff.sourceId, buff);
        expireTimer.schedule(buff.sourceId, buff.startTime + buff.duration);
    }

    private void removeBuff(int sourceId) {
        entries.remove(sourceId);
        expireTimer.cancel(sourceId);
    }

    /** 应用报告中的 L1 变更：整源移除（含到期定时器取消）与逐槽驱逐 */
    private void applyRemovals(EffectChangeReport report) {
        for (Integer sourceId : report.removeEntries) {
            removeBuff(sourceId);
        }
        for (Pair<Integer, EffectType> evict : report.evictSlots) {
            BuffStatus buff = entries.get(evict.getLeft());
            if (buff != null) {
                buff.effects.removeIf(es -> es.type == evict.getRight());
            }
        }
    }

    // ── 全量取消 ──

    void cancelAllBuffs(boolean softcancel) {
        if (softcancel) {
            try (var ignored = Locks.acquire(lock, owner.chrLock)) {
                cancelEffectFromBuffStat(EffectType.SUMMON);
                cancelEffectFromBuffStat(EffectType.PUPPET);

                active.clear();

                for (Integer srcid : new ArrayList<>(entries.keySet())) {
                    removeBuff(srcid);
                }
            }
        } else {
            List<Integer> sourceIds;
            try (var ignored = Locks.acquire(lock, owner.chrLock)) {
                sourceIds = new ArrayList<>(entries.keySet());
            }
            for (Integer sourceId : sourceIds) {
                cancelBuff(sourceId, false);
            }
        }
    }

    // ── 编排：注册/取消（调 L2 拿报告 → 应用 L1 → 委托 L3） ──

    boolean cancelBuff(int sourceId, boolean overwrite) {
        boolean isMagicDoor = false;
        boolean ret = false;

        try (var ignored = Locks.acquire(owner.prtLock, lock)) {
            BuffStatus buff = entries.get(sourceId);
            if (buff == null) {
                return false;
            }
            isMagicDoor = buff.data.isMagicDoor();

            EffectChangeReport report = BuffSelector.selectCancellation(entries, active.effects, buff, overwrite, owner);
            if (!report.abort) {
                applyRemovals(report);
                active.applyReport(report);

                ret = !report.removedStats.isEmpty();
            }
        }

        active.refreshLocalStats();   // 锁外刷新派生属性（updateLocalStats 锁序约束，见 ActiveBuffs.refreshLocalStats）

        if (isMagicDoor && ret) {
            try (var ignored = Locks.acquire(owner.prtLock, lock)) {
                if (!containsSourceId(Priest.MYSTIC_DOOR)) {
                    Door.attemptRemoveDoor(owner);
                }
            }
        }

        return ret;
    }

    void cancelEffectFromBuffStat(EffectType effectType) {
        EffectStatus effect;

        try (var ignored = Locks.acquire(lock, owner.chrLock)) {
            effect = active.effects.get(effectType);
        }
        if (effect != null) {
            cancelBuff(effect.buff.sourceId, false);
        }
    }

    void cancelBuffStats(EffectType effectType) {
        try (var ignored = Locks.acquire(lock)) {
            EffectChangeReport report;
            try (var ignored2 = Locks.acquire(owner.chrLock)) {
                report = BuffSelector.selectSlotCancellation(entries, active.effects, effectType, owner);
            }
            if (report.abort) {
                return;
            }
            applyRemovals(report);
            active.applyReport(report);   // 应用+发布+伴随任务启动
        }

        active.refreshLocalStats();   // 锁外刷新派生属性
        active.cancelPlayerBuffs(Collections.singletonList(effectType));
    }

    void registerEffect(BuffEffectData effect, long starttime, long expirationtime) {
        EffectChangeReport report;
        try (var ignored = Locks.acquire(owner.prtLock, lock, owner.chrLock)) {
            report = BuffSelector.selectRegistration(entries, active.effects, effect, starttime, expirationtime, owner);
            addBuff(report.candidate);
            active.applyReport(report);   // 应用+发布+伴随任务启动（仅对最终部署者，被压制者不启动）
        }

        active.refreshLocalStats();   // 锁外刷新派生属性（原 owner.updateLocalStats，归属 L3）
    }

    void updateActiveEffects() {
        try (var ignored = Locks.acquire(lock)) {     // thanks davidlafriniere, maple006, RedHat for pointing a deadlock occurring here
            EffectChangeReport report;
            try (var ignored2 = Locks.acquire(owner.chrLock)) {
                report = BuffSelector.selectActiveEffects(entries, active.effects, owner);
            }
            active.applyReport(report);   // 应用+发布+伴随任务启动（活性重算后重选的伴随 buff）
        }

        active.refreshLocalStats();   // 锁外刷新派生属性
    }
}
