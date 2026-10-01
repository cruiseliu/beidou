package org.gms.client.job;

/**
 * 自动分配 AP 区间（JobDefinition.autoAssignAp 的元素）。
 *
 * 等级落在 (from, to] 内时，每次升级自动把 AP 按 apAutoAssignKey 对应的脚本分配掉
 * （from exclusive / to inclusive，语义同 LevelUpRange，按升级前等级 oldLevel 判定）。
 * 分配方式由脚本决定（scripts/server/ap_assigner/*.js 按 apAutoAssignKey 分派），
 * 区间只声明"哪些等级触发自动分配"。
 *
 * 例（新手 10 级以下）：{from=1, to=10} → oldLevel 2~11 升级时自动分配 AP。
 */
public record AutoAssignApRange(int from, int to) {

    /** oldLevel 是否落在 (from, to] 内 */
    public boolean matches(int oldLevel) {
        return oldLevel > from && oldLevel <= to;
    }
}
