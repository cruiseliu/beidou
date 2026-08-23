package org.gms.client.character;

import org.gms.client.JobEnum;
import org.gms.model.json.CharacterSpData;
import org.gms.util.Locks;

import java.util.Arrays;

/**
 * SP（技能点）：数据 + 全部 SP 逻辑（查询/获得/变更/加载解析）。
 * 持有 owner 反向引用，SP 操作经 stats.wLock/rLock 保护（通过 Locks）。
 * SP 不与属性/AP 共用 StatsUpdate 管道（无逻辑关联），公告走 owner.announceStatsUpdate。
 */
class CharacterSp {
    private final Character owner;

    /**
     * 每职业槽位的剩余 SP。旧实现是长度 10 的技能书数组，面向未实际支持的龙神(Evan)，
     * 现改为长度 2：下标 0 = 新手，1 = 其他职业（新手亦可由等级隐式推算，不依赖存储）。
     * 后续改为动态长度（= 转职次数），待职业系统重构后实施。
     */
    int[] remainingSp = new int[2];

    CharacterSp(Character owner) {
        this.owner = owner;
    }

    /** SP 数组下标：新手 0，非新手 1 */
    static int indexOf(int jobId) {
        return JobEnum.getById(jobId).isBeginnerJob() ? 0 : 1;
    }

    int getRemainingSp(int jobId) {
        try (var ignored = Locks.acquire(owner.stats.rLock)) {
            return remainingSp[indexOf(jobId)];
        }
    }

    int[] getRemainingSps() {
        try (var ignored = Locks.acquire(owner.stats.rLock)) {
            return Arrays.copyOf(remainingSp, remainingSp.length);
        }
    }

    void setRemainingSp(int remainingSp, int jobId) {
        this.remainingSp[indexOf(jobId)] = remainingSp;
    }

    void setRemainingSp(int[] sps) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            System.arraycopy(sps, 0, remainingSp, 0, Math.min(sps.length, remainingSp.length));
        }
    }

    /**
     * 应用指定职业的 SP 目标值（绝对值），返回应用后的值供调用方拼装公告。
     * silent = true 时不发包（如 resetStats 需与属性变更合并为一次公告）。
     */
    int changeRemainingSp(int remainingSp, int jobId, boolean silent) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            setRemainingSp(remainingSp, jobId);
            int applied = this.remainingSp[indexOf(jobId)];
            if (!silent) {
                owner.stats.announceStatsUpdate(new java.util.HashMap<>(
                        java.util.Map.of(org.gms.client.PacketStat.AVAILABLESP, applied)));
            }
            return applied;
        }
    }

    void gainSp(int deltaSp, int jobId, boolean silent) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            int idx = indexOf(jobId);
            changeRemainingSp(Math.max(0, remainingSp[idx] + deltaSp), jobId, silent);
        }
    }

    // ── 持久化数据转换（sp 域；信封组装在 Character.toData/applyData） ──

    CharacterSpData toData() {
        CharacterSpData d = new CharacterSpData();
        d.remainingSp = remainingSp.clone();
        return d;
    }

    void applyData(CharacterSpData d) {
        System.arraycopy(d.remainingSp, 0, remainingSp, 0, Math.min(d.remainingSp.length, remainingSp.length));
    }

    /** 解析持久化的 "0,0" 职业槽位串并整体载入；旧格式多余的槽位丢弃 */
    void loadCharSkillPoints(String[] skillPoints) {
        int[] sps = new int[skillPoints.length];
        for (int i = 0; i < skillPoints.length; i++) {
            sps[i] = Integer.parseInt(skillPoints[i]);
        }
        setRemainingSp(sps);
    }
}
