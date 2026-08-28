package org.gms.remote;

import java.util.List;

/**
 * 语义模块：域归属见类型注释；wire 组装归后端私有（多对多约束见 doc/09 §5.2）。
 */
/** 背包资产域 */
public interface InventoryModule {
    /** 背包槽变更通知（并堆/新槽/移动/移除） */
    void updateInventory(List<SlotChange> changes);

    /** 背包满提示（v83：SHOW_STATUS_INFO(0xff)，空 INVENTORY_OPERATION 复用语义） */
    void announceInventoryFull();
}
