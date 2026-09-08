package org.gms.remote.modules.inventory.server;

import java.util.List;

import org.gms.remote.ServerEvent;

/** 背包槽位变更通知（并堆/新槽/移动/移除；原 SemanticEvent.InventoryMods）。
 *  含宠物物品时已由版本实现在入域时冻结为 {@link org.gms.remote.gms083.server.events.FrozenInventoryEvent}。 */
public record InventoryModsEvent(List<SlotChange> changes) implements ServerEvent {
}
