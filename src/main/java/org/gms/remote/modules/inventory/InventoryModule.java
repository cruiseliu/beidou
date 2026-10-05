package org.gms.remote.modules.inventory;

import org.gms.remote.AbstractModule;
import org.gms.remote.modules.inventory.server.InventoryFullEvent;
import org.gms.remote.modules.inventory.server.InventoryModsEvent;
import org.gms.remote.modules.inventory.server.SlotChange;

import java.util.List;

/** 背包资产域（语义基类）。 */
public abstract class InventoryModule extends AbstractModule {

    /** 背包槽变更通知（并堆/新槽/移动/移除）；宠物槽位的冻结归版本统一冻结门 */
    public final void updateInventory(List<SlotChange> changes) {
        post(new InventoryModsEvent(changes));
    }

    /** 背包满提示（v83：SHOW_STATUS_INFO(0xff)，空 INVENTORY_OPERATION 复用语义） */
    public final void announceInventoryFull() {
        post(new InventoryFullEvent());
    }

    public interface Handler {
        /** PET_FOOD：脚本钩子道具使用（喂食等，onUse 钩子驱动）。 */
        void useItem(int slotIndex, int slotId);

        /** USE_ITEM：消耗品使用（效果型道具——药水/卷轴/解除药水，效果应用归 gameplay）。 */
        void consumeItem(int slotIndex, int itemId);
    }
}
