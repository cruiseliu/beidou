package org.gms.remote;

/**
 * 事务段（ScopeLog）可存储的记录单元：语义事件（{@link org.gms.remote.out.events.SemanticEvent}）与版本实现
 * 在事件入域时派生的冻结事件（如 v83 FrozenInventoryEvent）共通的最高类型。
 * 见 gms-server/doc/package-client.md §1/§2。
 */
public interface ScopeRecord {
}
