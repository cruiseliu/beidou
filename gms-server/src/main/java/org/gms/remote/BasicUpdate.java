package org.gms.remote;

/**
 * updateBasic 操作的语义载荷：角色基础标识（jobId/level/exp，归 basic 域）的新值。
 * 三字段独立可选，同一事务内多次提交时同字段后写覆盖；v83 中与 stats/sp 合并进
 * 同一 STAT_CHANGED 封包。
 */
public final class BasicUpdate {
    private Integer jobId;
    private Integer level;
    private Long exp;

    public BasicUpdate jobId(int jobId) {
        this.jobId = jobId;
        return this;
    }

    public BasicUpdate level(int level) {
        this.level = level;
        return this;
    }

    public BasicUpdate exp(long exp) {
        this.exp = exp;
        return this;
    }

    public Integer jobId() {
        return jobId;
    }

    public Integer level() {
        return level;
    }

    public Long exp() {
        return exp;
    }
}
