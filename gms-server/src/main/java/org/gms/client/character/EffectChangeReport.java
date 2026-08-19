package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.server.BuffEffectData;
import org.gms.util.Pair;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** L2 纯选择层（BuffSelector）产出的变更报告：只描述"要做什么"，不执行任何状态修改。
 *
 *  按归属分三组，由调用方（CharacterBuffs）分别应用：
 *  - L1 生命周期变更（removeEntries / evictSlots）：应用到 entries 与到期定时器；
 *  - L3 激活表变更（deploy / deactivated / removeActiveTypes）：应用到 ActiveBuffs.effects；
 *  - L3 对外发布（lostSlots / reannounced / battleshipRefresh / stopChairTask / unregisterMountHunger）：
 *    交给 publish() 发包与伴随清理。
 *
 *  本类取代原 CharacterBuffs 内部类 EffectChangeReport，并新增注册/取消链所需的 L1 变更字段。 */
class EffectChangeReport {
    /** 注册候选（selectRegistration 构造，含槽位 EffectStatus），供调用方 addBuff 入册 */
    BuffStatus candidate;

    // ── L1 生命周期变更 ──

    /** 需整源移除的 buff（applyRemovals 一并取消其到期定时器） */
    final Set<Integer> removeEntries = new LinkedHashSet<>();

    /** 需从在册 buff 逐槽驱逐的 (sourceId, 槽位)；驱逐后变空的源同时进入 removeEntries */
    final List<Pair<Integer, EffectType>> evictSlots = new ArrayList<>();

    /** 本次取消实际影响的槽位集合（驱动 cancelBuff 的返回值判定；含被重选填补的槽位） */
    final Set<EffectType> removedStats = new LinkedHashSet<>();

    // ── L3 激活表变更 ──

    /** 需写入激活表的持有者（注册部署 / 取消重选 / 活性重算结果） */
    final List<EffectStatus> deploy = new ArrayList<>();

    /** 从激活表移除的持有者（驱动 CANCEL_BUFF 与 RECOVERY/召唤物/龙血/HPREC 伴随清理） */
    final List<EffectStatus> deactivated = new ArrayList<>();

    /** 需按槽位类型直接移除的激活持有者（updateActiveEffects 活性重算；不触发伴随清理） */
    final Set<EffectType> removeActiveTypes = new LinkedHashSet<>();

    // ── L3 对外发布 ──

    /** 已无激活者的槽位，需向客户端通告取消 */
    final Set<EffectType> lostSlots = new LinkedHashSet<>();

    /** 需重发 updateBuffEffect 的（效果, 基准时刻），拓扑序在前、priority 源在后 */
    final List<Pair<BuffEffectData, Long>> reannounced = new ArrayList<>();

    /** 传播实际发生（retrievedStats 非空）时需重发骑船显示 */
    boolean battleshipRefresh = false;
    boolean stopChairTask = false;
    boolean unregisterMountHunger = false;

    /** selectSlotCancellation 防御性中止（entries 中目标已消失）；置位时调用方应跳过应用与发布 */
    boolean abort = false;
}
