package org.gms.remote.modules.inventory.server;

import org.gms.remote.ServerEvent;

/** 背包满提示（v83：SHOW_STATUS_INFO(0xff)，空 INVENTORY_OPERATION 复用语义；原 SemanticEvent.InventoryFull）。 */
public record InventoryFullEvent() implements ServerEvent {
}
