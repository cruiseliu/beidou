package org.gms.remote;

import java.util.List;

/**
 * 语义事件的不可变载体：作用域期间语义调用的原始发生序记录（不做合并——合并是消费侧的事）。
 * 事件即意图，与"未开域逐条即时调用"共用同一组处理函数。
 * 携带可变实体的事件须为冻结快照，契约见 gms-server/doc/package-client.md §1。
 */
public sealed interface SemanticEvent extends ScopeRecord {

    record Stats(StatsUpdate update) implements SemanticEvent {}

    record Sp(SpUpdate update) implements SemanticEvent {}

    record Skill(SkillUpdate update) implements SemanticEvent {}

    record SkillRemove(int skillId) implements SemanticEvent {}

    record Basic(BasicUpdate update) implements SemanticEvent {}

    record UnlockActions() implements SemanticEvent {}

    record CooldownClear(int skillId) implements SemanticEvent {}

    record InventoryMods(List<SlotChange> changes) implements SemanticEvent {}

    record InventoryFull() implements SemanticEvent {}

    /** 拾取过滤共享列表快照（构造时冻结）。帧按 commit 时刻的召唤集逐宠展开（协议 per-pet，配置共享）。 */
    record PetIgnoreList(int cid, List<Integer> itemIds) implements SemanticEvent {}

    /** 宠物面板快照（构造时冻结的 wire 事实，tameness 原样携带、截断在翻译层）；
     *  levelUp = 本次变更跨越等级边界（演出判定依据）。 */
    record PetPanel(int cid, byte petIndex, short pos, long petId, int itemId, String name,
                    int level, int tameness, int fullness, int flags,
                    boolean alive, long expiration, boolean levelUp) implements SemanticEvent {}
}
