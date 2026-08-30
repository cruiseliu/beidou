package org.gms.remote;

import java.util.List;

/**
 * 语义事件的不可变载体：作用域期间语义调用的原始发生序记录（不做任何合并——
 * 合并是消费侧的事，见 doc/09 §5.2）。commit 时由实现按序分发给后端处理。
 *
 * <p>事件即意图，与"未开域逐条即时调用"共用同一组处理函数——批量化的正确性
 * 论证立足于此。{@code InventoryMods} 持有的列表在被唯一一次处理前不得变更
 * 快照的 clear() 消费动作只发生在编码期，语义记录本身不可变、无需清理。
 */
public sealed interface SemanticEvent {

    record Stats(StatsUpdate update) implements SemanticEvent {}

    record Sp(SpUpdate update) implements SemanticEvent {}

    record Skill(SkillUpdate update) implements SemanticEvent {}

    record SkillRemove(int skillId) implements SemanticEvent {}

    record Basic(BasicUpdate update) implements SemanticEvent {}

    record UnlockActions() implements SemanticEvent {}

    record CooldownClear(int skillId) implements SemanticEvent {}

    record InventoryMods(List<SlotChange> changes) implements SemanticEvent {}

    record InventoryFull() implements SemanticEvent {}
}
