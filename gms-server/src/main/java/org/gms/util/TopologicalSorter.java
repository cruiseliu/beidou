package org.gms.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 拓扑排序（泛型工具）：按偏序约束排列节点。
 *
 * 实现：Kahn 变体。每步取出"前驱数量最少"的节点（正常情况下即零前驱节点；并列取迭代序首个），
 * 并将其约束的后继前驱数减一。约束成环时无零前驱节点，最少前驱节点即破环点，保证算法终止。
 * 节点数通常很小（个位到十位），O(n²) 对扫描即可。
 */
public final class TopologicalSorter {
    private TopologicalSorter() {
    }

    /**
     * @param nodes    待排序节点（迭代顺序作为同位节点的稳定优先级）
     * @param precedes 偏序约束：precedes.test(a, b) 为 true 表示 a 必须排在 b 之前
     * @return 满足全部（无环部分）约束的节点排列
     */
    public static <N> List<N> sort(Collection<N> nodes, BiPredicate<N, N> precedes) {
        List<N> remaining = new ArrayList<>(nodes);
        int[] predCount = new int[remaining.size()];
        for (int i = 0; i < remaining.size(); i++) {
            for (int j = 0; j < remaining.size(); j++) {
                if (i != j && precedes.test(remaining.get(j), remaining.get(i))) {
                    predCount[i]++;
                }
            }
        }

        List<N> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            int pick = 0;
            for (int i = 1; i < remaining.size(); i++) {
                if (predCount[i] < predCount[pick]) {
                    pick = i;
                }
            }

            N node = remaining.remove(pick);
            result.add(node);

            // 与 remaining 同步移除计数，再将其约束的后继前驱数减一
            System.arraycopy(predCount, pick + 1, predCount, pick, remaining.size() - pick);
            for (int i = 0; i < remaining.size(); i++) {
                if (precedes.test(node, remaining.get(i))) {
                    predCount[i]--;
                }
            }
        }
        return result;
    }
}
