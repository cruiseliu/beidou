package org.gms.client.character;

import org.gms.client.JobEnum;
import org.gms.client.job.JobRegistry;
import org.gms.model.json.CharacterSpData;
import org.gms.remote.modules.skills.server.SpUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.TreeMap;

/**
 * SP（技能点）：数据 + 全部 SP 逻辑（查询/获得/分配扣减/加载解析）。
 * 持有 owner 反向引用；无锁直写（player strand 单线程纪律，原借 stats 锁已随快照机制退役）。
 * SP 不与属性/AP 共用 StatsUpdate 管道（无逻辑关联），公告走 remote 隔离层 updateSp（技能域）。
 *
 * <p>存储为 jobId → 剩余 SP 的分桶 Map（新手槽 jobId=0 与职业技能点槽分立）；
 * 分配优先级见 {@link #spendSpForSkill}。发包显示值：当前职业为新手时发新手槽值
 * （客户端本地计算显示），否则发非新手槽之和——与旧版（下标 0/1 双槽）字节一致。
 */
class CharacterSp {
    private static final Logger log = LoggerFactory.getLogger(CharacterSp.class);

    private final Character owner;

    /** jobId → 剩余 SP（TreeMap：分配回溯按 jobId 升序遍历） */
    private final TreeMap<Integer, Integer> remainingSp = new TreeMap<>();

    CharacterSp(Character owner) {
        this.owner = owner;
    }

    private static boolean isBeginnerJob(int jobId) {
        JobEnum job = JobEnum.getById(jobId);
        return job != null && job.isBeginnerJob();
    }

    int getRemainingSp(int jobId) {
        return remainingSp.getOrDefault(jobId, 0);
    }

    /**
     * 客户端显示值（新手职业=新手桶单值，非新手=非新手桶之和）。
     * 迁移期仅供旧路径拼包使用（CharacterLevel/CharacterJob 手拼 statup、
     * getCharInfo 快照、DEBUG_RES）；显示值概念的正确居所在 v83 编码器
     * （V83RemoteClient.visibleSp），这些调用方随旧路径迁移后本方法删除。
     */
    int getClientVisibleSp() {
        int cur = owner.job.def().jobId();
        if (isBeginnerJob(cur)) {
            return remainingSp.getOrDefault(cur, 0);
        }
        int sum = 0;
        for (Map.Entry<Integer, Integer> e : remainingSp.entrySet()) {
            if (!isBeginnerJob(e.getKey())) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    void setRemainingSp(int remainingSp, int jobId) {
        this.remainingSp.put(jobId, remainingSp);
    }

    /** 整体载入（角色复制/加载） */
    void setAllSp(Map<Integer, Integer> sps) {
        remainingSp.clear();
        if (sps != null) {
            remainingSp.putAll(sps);
        }
    }

    Map<Integer, Integer> snapshotSp() {
        return new TreeMap<>(remainingSp);
    }

    private void announceSp() {
        try (var _b = owner.remote().batch()) {
            owner.remote().skills().updateSp(new SpUpdate(owner.job.def().jobId(), remainingSp));
            owner.remote().basic().unlockActions();
        }
    }

    /** 全部桶（jobId 升序的值数组；v83 SP 表职业分桶块用，Evan 未实现语义待定） */
    int[] getSpBuckets() {
        int[] arr = new int[remainingSp.size()];
        int i = 0;
        for (int v : remainingSp.values()) {
            arr[i++] = v;
        }
        return arr;
    }

    /**
     * 应用指定职业的 SP 目标值（绝对值），返回应用后的值供调用方拼装公告。
     * silent = true 时不发包（如升级/转职由 CharacterLevel/CharacterJob 自行组包）。
     */
    int changeRemainingSp(int remainingSp, int jobId, boolean silent) {
        setRemainingSp(remainingSp, jobId);
        if (!silent) {
            announceSp();
        }
        return this.remainingSp.getOrDefault(jobId, 0);
    }

    void gainSp(int deltaSp, int jobId, boolean silent) {
        changeRemainingSp(Math.max(0, remainingSp.getOrDefault(jobId, 0) + deltaSp), jobId, silent);
    }

    /**
     * 分配技能时的 SP 扣减（成功则扣 1 点并发包，返回 true；无可用 SP 返回 false）。
     * 优先级：
     * 1. 技能所属职业（skillId / 10000）的桶；
     * 2. 不足时按 jobId 升序遍历有 SP 的桶，取第一个 spCompatibleWith(技能职业) 的桶；
     * 3. 无 compatible 桶时使用当前职业桶并记 warning（新手槽永远不 compatible，
     *    会被第 3 步以当前职业=新手身份消耗——保持旧版新手技能可用）。
     */
    boolean spendSpForSkill(int skillId) {
        int skillJobId = skillId / 10000;
        Integer donor = null;
        if (remainingSp.getOrDefault(skillJobId, 0) > 0) {
            donor = skillJobId;
        } else {
            for (Map.Entry<Integer, Integer> e : remainingSp.entrySet()) {
                if (e.getValue() > 0 && JobRegistry.of(e.getKey()).spCompatibleWith(skillJobId)) {
                    donor = e.getKey();
                    break;
                }
            }
        }
        int curJobId = owner.job.def().jobId();
        if (donor == null) {
            if (remainingSp.getOrDefault(curJobId, 0) > 0) {
                donor = curJobId;
                log.warn("Chr {} 花费 SP 于职业 {} 技能 {}：无 compatible SP 桶，回退消耗当前职业 {} 的 SP",
                        owner.getName(), skillJobId, skillId, curJobId);
            } else {
                return false;
            }
        }
        remainingSp.put(donor, remainingSp.get(donor) - 1);
        announceSp();
        return true;
    }

    // ── 持久化数据转换（sp 域；信封组装在 Character.toData/applyData） ──

    CharacterSpData toData() {
        CharacterSpData d = new CharacterSpData();
        d.remainingSp = snapshotSp();
        return d;
    }

    void applyData(CharacterSpData d) {
        setAllSp(d.remainingSp);
    }
}
