package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.JobEnum;
import org.gms.config.GameConfig;
import org.gms.constants.id.ItemId;
import org.gms.server.BuffEffectData;
import org.gms.util.Pair;
import org.gms.util.TopologicalSorter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * L2 纯选择层：以在册表（entries）+ 激活表（active）为输入，产出 {@link EffectChangeReport}。
 *
 * 契约：
 * - 零副作用——不修改 entries / active / 定时器 / 发包 / 伴随任务；
 *   需要"变更后状态"时基于入参构建局部投影（projectedEntries / projectedActive），
 *   变更本身只记录在报告中，由调用方（CharacterBuffs）统一应用。
 * - 纯计算——同槽位最佳选择、压制/部署判定、取消后重选、驱逐、传播重发集计算全部在此完成。
 *
 * 原实现（CharacterBuffs.computeRegistration/computeCancellation/cancelBuffStats 及
 * findBestEffect/extractLeastRelevantStatEffectsIfFull/fillReselectAndReannounce/
 * computeReannounce/propagatePriorityBuffEffectUpdates 等）平移至此并纯化：
 * 所有"写入"改为"记录到 report"，下游计算改用投影状态。
 */
class BuffSelector {
    private static final Logger log = LoggerFactory.getLogger(BuffSelector.class);

    // ── 注册选择 ──

    /** 注册：构造候选 BuffStatus，判定部署/压制与传播重发集。
     *  报告携带 candidate（供调用方 addBuff）与 deploy（部署到激活表）。 */
    static EffectChangeReport selectRegistration(
            Map<Integer, BuffStatus> entries,
            Map<EffectType, EffectStatus> active,
            BuffEffectData effect,
            long starttime,
            long expirationtime,
            Character owner) {

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

        EffectChangeReport report = new EffectChangeReport();
        report.candidate = buff;

        boolean effectActive = effect.isActive(owner);
        if (GameConfig.getServerBoolean("use_buff_most_significant")) {
            List<EffectStatus> toDeploy = new ArrayList<>(appliedStatups.size());
            Set<EffectType> retrievedStats = new LinkedHashSet<>();
            for (EffectStatus statMbsvh : appliedStatups) {
                EffectStatus incumbent = active.get(statMbsvh.type);

                if (effectActive) {
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
            for (EffectStatus mbsvh : flattenEffects(entries)) {
                if (isPriorityBuffSourceId(mbsvh.getData().getBuffSourceId())) {
                    for (Pair<EffectType, Integer> p : mbsvh.getData().getStatups()) {
                        if (updated.contains(p.getLeft())) {
                            retrievedStats.add(p.getLeft());
                        }
                    }
                }
            }

            report.deploy.addAll(toDeploy);

            Map<EffectType, EffectStatus> projActive = new LinkedHashMap<>(active);
            for (EffectStatus es : toDeploy) {
                projActive.put(es.type, es);
            }
            Map<Integer, BuffStatus> projEntries = new LinkedHashMap<>(entries);
            projEntries.put(sourceid, buff);

            Map<Integer, Pair<BuffEffectData, Long>> retrievedEffects = new LinkedHashMap<>();
            if (effectActive) {
                retrievedEffects.put(sourceid, new Pair<>(effect, starttime));
            }
            computeReannounce(report, projEntries, projActive, retrievedEffects, retrievedStats, new LinkedHashSet<>(), owner);
        } else {
            report.deploy.addAll(effectActive ? appliedStatups : new ArrayList<>());
        }
        return report;
    }

    // ── 取消选择 ──

    /** 取消：目标 buff 已在册，选择整源摘除或逐槽驱逐，重选被影响槽位并算传播重发集。 */
    static EffectChangeReport selectCancellation(
            Map<Integer, BuffStatus> entries,
            Map<EffectType, EffectStatus> active,
            BuffStatus target,
            boolean overwrite,
            Character owner) {

        EffectChangeReport report = new EffectChangeReport();
        List<EffectStatus> buffstats = null;
        EffectType effectType;
        if (!overwrite) {   // is removing the source effect, meaning every effect from this srcid is being purged
            report.removeEntries.add(target.sourceId);
            buffstats = target.effects;
        } else if ((effectType = getSingletonStatupFromEffect(target)) != null) {   // removing all effects of a buff having non-shareable buff stat.
            if (active.get(effectType) != null) {
                report.removeEntries.add(target.sourceId);
                buffstats = target.effects;
            }
        }

        if (buffstats == null) {            // all else, is dropping ALL current statups that uses same stats as the given effect
            buffstats = extractLeastRelevantStatEffectsIfFull(entries, target, report);
        }

        if (target.data.isMapChair()) {
            report.stopChairTask = true;
        }

        report.deactivated.addAll(computeDeactivated(active, buffstats));
        if (target.data.isMonsterRiding()) {
            report.unregisterMountHunger = true;
        }

        if (!overwrite) {
            for (EffectStatus es : buffstats) {
                report.removedStats.add(es.type);
            }
        }

        Map<Integer, BuffStatus> projEntries = projectedEntries(entries, report.removeEntries, report.evictSlots);
        Map<EffectType, EffectStatus> projActive = new LinkedHashMap<>(active);
        for (EffectStatus es : report.deactivated) {
            projActive.remove(es.type);
        }
        fillReselectAndReannounce(report, projEntries, projActive, report.removedStats, owner);
        return report;
    }

    /** 按槽取消：摘除所有在册 buff 的该槽位，重选该槽最佳并算传播。 */
    static EffectChangeReport selectSlotCancellation(
            Map<Integer, BuffStatus> entries,
            Map<EffectType, EffectStatus> active,
            EffectType slotType,
            Character owner) {

        EffectChangeReport report = new EffectChangeReport();
        List<Pair<Integer, EffectStatus>> cancelList = new LinkedList<>();
        for (BuffStatus buff : entries.values()) {
            for (EffectStatus effect : buff.effects) {
                if (effect.type == slotType) {
                    cancelList.add(new Pair<>(buff.sourceId, effect));
                }
            }
        }

        for (Pair<Integer, EffectStatus> p : cancelList) {
            int sourceId = p.getLeft();
            EffectStatus effect = p.getRight();

            BuffStatus buff = entries.get(sourceId);
            if (buff == null) {
                report.abort = true;   // 防御：与当前持锁下的可达性一致，原实现直接 return 整个方法
                return report;
            }

            report.evictSlots.add(new Pair<>(sourceId, slotType));
            if (buff.effects.size() == 1) {   // 逐槽移除后变空（每 buff 每槽至多一项）
                report.removeEntries.add(sourceId);
            }
            report.deactivated.addAll(computeDeactivated(active, Collections.singletonList(effect)));
        }

        EffectStatus best = findBestEffect(projectedEntries(entries, report.removeEntries, report.evictSlots), slotType, owner);
        if (best != null) {
            report.deploy.add(best);
        }
        return report;
    }

    // ── 活性重算（地图/组队变更后） ──

    /** 活性重算：找出活性翻转的槽位，摘除旧持有者并重选。 */
    static EffectChangeReport selectActiveEffects(
            Map<Integer, BuffStatus> entries,
            Map<EffectType, EffectStatus> active,
            Character owner) {

        EffectChangeReport report = new EffectChangeReport();
        Set<EffectType> updatedBuffs = new LinkedHashSet<>();
        Set<BuffEffectData> activeEffects = new LinkedHashSet<>();

        for (EffectStatus mse : active.values()) {
            activeEffects.add(mse.getData());
        }

        for (BuffStatus buff : entries.values()) {
            if (isUpdatingEffect(activeEffects, buff.data, owner)) {
                for (Pair<EffectType, Integer> p : buff.data.getStatups()) {
                    updatedBuffs.add(p.getLeft());
                }
            }
        }

        report.removeActiveTypes.addAll(updatedBuffs);

        Map<EffectType, EffectStatus> projActive = new LinkedHashMap<>(active);
        for (EffectType mbs : updatedBuffs) {
            projActive.remove(mbs);
        }
        fillReselectAndReannounce(report, entries, projActive, updatedBuffs, owner);
        return report;
    }

    // ── 纯计算助手 ──

    /** 纯同槽重选：扫描在册表返回最佳（value 最大者优先，同值取 statups 更多者），不写激活表 */
    private static EffectStatus findBestEffect(Map<Integer, BuffStatus> entries, EffectType effectType, Character owner) {
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
        return bestEffect;
    }

    /** 纯摘除计算：对 buffstats 逐项查激活表，返回同源 holder 集合（不修改激活表） */
    private static List<EffectStatus> computeDeactivated(Map<EffectType, EffectStatus> active, List<EffectStatus> buffstats) {
        List<EffectStatus> deactivated = new ArrayList<>(buffstats.size());
        for (EffectStatus effect : buffstats) {
            int sourceId = effect.getData().getBuffSourceId();

            EffectStatus holder = active.get(effect.type);
            if (holder != null && holder.getData().getBuffSourceId() == sourceId) {
                deactivated.add(holder);
            }
        }
        return deactivated;
    }

    /** 纯驱逐：统计各槽位在册数量，对目标 statups 中超限（max_monitored_buff_stats）的槽位驱逐最小者，
     *  驱逐决策写入 report.evictSlots / report.removeEntries（驱逐后变空的源）。 */
    private static List<EffectStatus> extractLeastRelevantStatEffectsIfFull(
            Map<Integer, BuffStatus> entries,
            BuffStatus target,
            EffectChangeReport report) {

        List<EffectStatus> extractedStatBuffs = new ArrayList<>();
        Map<EffectType, Byte> stats = new LinkedHashMap<>();
        Map<EffectType, EffectStatus> minStatBuffs = new LinkedHashMap<>();
        Map<Integer, Integer> evictedCount = new LinkedHashMap<>();   // 驱逐后变空判定（同源多槽驱逐需累计）

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
        for (Pair<EffectType, Integer> efstat : target.data.getStatups()) {
            effectTypes.add(efstat.getLeft());
        }

        for (Map.Entry<EffectType, Byte> it : stats.entrySet()) {
            boolean uniqueBuff = isSingletonStatup(it.getKey());

            if (it.getValue() >= (!uniqueBuff ? GameConfig.getServerByte("max_monitored_buff_stats") : 1) && effectTypes.contains(it.getKey())) {
                EffectStatus effect = minStatBuffs.get(it.getKey());

                report.evictSlots.add(new Pair<>(effect.buff.sourceId, it.getKey()));
                int count = evictedCount.merge(effect.buff.sourceId, 1, Integer::sum);
                if (count == effect.buff.effects.size()) {
                    report.removeEntries.add(effect.buff.sourceId);
                }
                extractedStatBuffs.add(effect);
            }
        }
        return extractedStatBuffs;
    }

    /** 投影在册表：去掉整源移除，克隆受驱逐影响 buff 的槽位列表后逐槽剔除 */
    private static Map<Integer, BuffStatus> projectedEntries(
            Map<Integer, BuffStatus> entries,
            Set<Integer> removed,
            List<Pair<Integer, EffectType>> evictions) {

        Map<Integer, BuffStatus> proj = new LinkedHashMap<>();
        for (Map.Entry<Integer, BuffStatus> e : entries.entrySet()) {
            if (!removed.contains(e.getKey())) {
                proj.put(e.getKey(), e.getValue());
            }
        }
        for (Pair<Integer, EffectType> evict : evictions) {
            BuffStatus b = proj.get(evict.getLeft());
            if (b == null || b.effects.stream().noneMatch(es -> es.type == evict.getRight())) {
                continue;
            }
            BuffStatus clone = new BuffStatus(b.sourceId, b.data, b.startTime, b.duration);
            clone.effects.addAll(b.effects);
            clone.effects.removeIf(es -> es.type == evict.getRight());
            proj.put(b.sourceId, clone);
        }
        return proj;
    }

    /** 重选+传播（取消链的公共尾部）：对每个受影响槽重选最佳（写入投影与 report.deploy），再计算重发集 */
    private static void fillReselectAndReannounce(
            EffectChangeReport report,
            Map<Integer, BuffStatus> projEntries,
            Map<EffectType, EffectStatus> projActive,
            Set<EffectType> removedTypes,
            Character owner) {

        Set<EffectType> retrievedStats = new LinkedHashSet<>();

        for (EffectType effectType : removedTypes) {
            EffectStatus best = findBestEffect(projEntries, effectType, owner);
            if (best != null) {
                projActive.put(effectType, best);
                report.deploy.add(best);
            }

            EffectStatus effect = projActive.get(effectType);
            if (effect != null) {
                for (Pair<EffectType, Integer> statup : effect.getData().getStatups()) {
                    retrievedStats.add(statup.getLeft());
                }
            }
        }

        computeReannounce(report, projEntries, projActive, new LinkedHashMap<>(), retrievedStats, removedTypes, owner);
    }

    /** 传播计算（纯读投影状态）：填 report 的 lostSlots/reannounced/battleshipRefresh */
    private static void computeReannounce(
            EffectChangeReport report,
            Map<Integer, BuffStatus> projEntries,
            Map<EffectType, EffectStatus> projActive,
            Map<Integer, Pair<BuffEffectData, Long>> retrievedEffects,
            Set<EffectType> retrievedStats,
            Set<EffectType> removedStats,
            Character owner) {

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
            EffectStatus effect = projActive.get(effectType);
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

        boolean mageJob = owner.getJobStyle() == JobEnum.MAGICIAN;
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

        for (Pair<Integer, Pair<BuffEffectData, Long>> lmse : propagatePriorityBuffEffectUpdates(projEntries, projActive, retrievedStats)) {
            report.reannounced.add(lmse.getRight());
        }
    }

    /** priority 源（香水）的客户端感知覆盖：优先把 priority 源的槽位值"伪装"为当前生效 */
    private static List<Pair<Integer, Pair<BuffEffectData, Long>>> propagatePriorityBuffEffectUpdates(
            Map<Integer, BuffStatus> projEntries,
            Map<EffectType, EffectStatus> projActive,
            Set<EffectType> retrievedStats) {

        List<Pair<Integer, Pair<BuffEffectData, Long>>> priorityUpdateEffects = new LinkedList<>();
        Map<EffectStatus, BuffEffectData> yokeStats = new LinkedHashMap<>();

        // priority buffsources: override buffstats for the client to perceive those as "currently buffed"
        Set<EffectStatus> mbsvhList = new LinkedHashSet<>(flattenEffects(projEntries));

        for (EffectStatus mbsvh : mbsvhList) {
            BuffEffectData mse = mbsvh.getData();
            int buffSourceId = mse.getBuffSourceId();
            if (isPriorityBuffSourceId(buffSourceId) && !hasActiveBuff(projActive, buffSourceId)) {
                for (Pair<EffectType, Integer> ps : mse.getStatups()) {
                    EffectType mbs = ps.getLeft();
                    if (retrievedStats.contains(mbs)) {
                        EffectStatus mbsvhe = projActive.get(mbs);
                        // this shouldn't even be null...
                        yokeStats.put(mbsvh, mbsvhe.getData());
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

    /** 传播更新的发送序（弱→强，最强者的包最后落地）。
     *  偏序约束：两效果共享某槽位且前者槽位值更低（更弱、被后者压制）→ 前者先发。 */
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

    private static EffectType getSingletonStatupFromEffect(BuffStatus buff) {
        for (Pair<EffectType, Integer> mbs : buff.data.getStatups()) {
            if (isSingletonStatup(mbs.getLeft())) {
                return mbs.getLeft();
            }
        }
        return null;
    }

    private static boolean isUpdatingEffect(Set<BuffEffectData> activeEffects, BuffEffectData mse, Character owner) {
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

    private static boolean hasActiveBuff(Map<EffectType, EffectStatus> active, int sourceid) {
        for (EffectStatus mbsvh : active.values()) {
            if (mbsvh.getData().getBuffSourceId() == sourceid) {
                return true;
            }
        }
        return false;
    }

    private static List<EffectStatus> flattenEffects(Map<Integer, BuffStatus> entries) {
        List<EffectStatus> ret = new ArrayList<>();
        for (BuffStatus buff : entries.values()) {
            ret.addAll(buff.effects);
        }
        return ret;
    }

    static boolean isSingletonStatup(EffectType effectType) {
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
}
