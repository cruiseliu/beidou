package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.client.inventory.ModifyInventory;
import org.gms.util.PacketCreator;

import java.util.ArrayList;
import java.util.List;

/**
 * INVENTORY_OPERATION：背包槽变更列表（并堆 mode 1 / 新槽 mode 0 / 移动 mode 2 / 移除 mode 3）。
 * 编码复用 PacketCreator.modifyInventory（格式逐字节一致）；同合并域多条变更一包。
 * 背包满提示（空列表 + SHOW_STATUS_INFO(0xff)）复用本 op 的 full 标记。
 */
final class ModifyInventoryOp implements V83Op {
    private final List<ModifyInventory> mods = new ArrayList<>();
    private boolean full = false;

    void mergeAll(List<ModifyInventory> additions) {
        mods.addAll(additions);
    }

    void markFull() {
        full = true;
    }

    @Override
    public boolean isEmpty() {
        return mods.isEmpty() && !full;
    }

    @Override
    public void sendTo(Client client) {
        if (full) {
            client.sendPacket(PacketCreator.getShowInventoryFull());
            full = false;
        }
        if (!mods.isEmpty()) {
            restoreChargeForWire(mods);
            client.sendPacket(PacketCreator.modifyInventory(true, mods));
            mods.clear();
        }
    }

    /**
     * wire 前把可充值物品的 charge 恢复成 quantity（v83 封包的数量字段 = 可使用次数，
     * 内部表示 quantity 恒 1）。ModifyInventory 构造时已 copy 快照，此处仅改快照，不碰真实背包。
     */
    private static void restoreChargeForWire(List<ModifyInventory> mods) {
        for (ModifyInventory mod : mods) {
            org.gms.client.inventory.ItemSlot snapshot = mod.getItem();
            if (snapshot != null && snapshot.isRechargeable()) {
                snapshot.setQuantity(snapshot.getItem().getCharge());
            }
        }
    }
}
