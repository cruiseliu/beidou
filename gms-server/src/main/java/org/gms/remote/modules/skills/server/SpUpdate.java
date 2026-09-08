package org.gms.remote.modules.skills.server;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * updateSp 操作的语义载荷：SP（技能点，归技能域，不属 stats）按职业分桶的原始事实。
 *
 * <p>自包含原则：载荷携带 jobId（当前职业）与全部分桶值，不携带任何显示值——
 * "客户端显示多少"（新手桶单显/非新手池求和、SP 表职业分桶块）是版本编码器的私事。
 * 构造时防御性拷贝为 TreeMap（jobId 升序），访问器只读。
 */
public record SpUpdate(int jobId, Map<Integer, Integer> spByJob) {
    public SpUpdate {
        spByJob = new TreeMap<>(spByJob);
    }

    @Override
    public Map<Integer, Integer> spByJob() {
        return Collections.unmodifiableMap(spByJob);
    }
}
