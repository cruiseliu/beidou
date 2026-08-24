package org.gms.remote;

/**
 * updateSp 操作的语义载荷：SP（技能点，归技能域，不属 stats）的新状态。
 *
 * <p>自包含原则：SP 语义上按职业分桶持有（见 CharacterSp），载荷携带 jobId、
 * 当前职业桶的显示值与全部分桶值，由版本编码器决定写单个 short 还是分桶变长块
 * （v83 SP 表职业，如龙神）。数组为只读传递，编码后不可再修改。
 */
public record SpUpdate(int jobId, int visibleSp, int[] spByBucket) {
}
