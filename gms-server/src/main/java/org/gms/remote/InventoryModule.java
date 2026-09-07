package org.gms.remote;

import java.util.List;
import org.gms.remote.in.events.UseItemEvent;

/**
 * 语义模块：域归属见类型注释；wire 组装归后端私有（多对多映射见 gms-server/doc/package-client.md §3）。
 */
/** 背包资产域 */
public interface InventoryModule {
    /** 背包槽变更通知（并堆/新槽/移动/移除） */
    void updateInventory(List<SlotChange> changes);

    /** 背包满提示（v83：SHOW_STATUS_INFO(0xff)，空 INVENTORY_OPERATION 复用语义） */
    void announceInventoryFull();

    interface In {
        default void useItem(UseItemEvent e) {}
    }
}
