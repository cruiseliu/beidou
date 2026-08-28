package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.client.inventory.ItemSlot;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;
import org.gms.remote.SlotChange;
import org.gms.util.PacketCreator;

import java.util.ArrayList;
import java.util.List;

/**
 * INVENTORY_OPERATION：直接从 {@link SlotChange} 语义记录组包（帧结构自
 * PacketCreator.modifyInventory 逐字节移植，不再经 legacy ModifyInventory 列表）。
 * 数量为绝对终值；可充值物品的 wire 数量 = 可使用次数（charge），经深拷贝快照注入、
 * 绝不改写共享槽位。同作用域多条变更一包；背包满提示（SHOW_STATUS_INFO(0xff)）
 * 复用本 op 的 full 标记。物品体复用 PacketCreator.addItemInfo 编码器（与
 * 商店/仓库/掉落同源，唯一保留的 PacketCreator 依赖点）。
 */
final class ModifyInventoryOp implements V83Op {
    private final List<SlotChange> changes = new ArrayList<>();
    private boolean full = false;

    void mergeAll(List<SlotChange> additions) {
        changes.addAll(additions);
    }

    void markFull() {
        full = true;
    }

    @Override
    public boolean isEmpty() {
        return changes.isEmpty() && !full;
    }

    @Override
    public void sendTo(Client client) {
        if (full) {
            client.sendPacket(PacketCreator.getShowInventoryFull());
            full = false;
        }
        if (!changes.isEmpty()) {
            client.sendPacket(encode(changes));
            changes.clear();
        }
    }

    private static Packet encode(List<SlotChange> changes) {
        OutPacket p = OutPacket.create(SendOpcode.INVENTORY_OPERATION);
        p.writeBool(true);   // updateTick 恒 true（远端路径的既有形态）
        p.writeByte(changes.size());
        int addMovement = -1;
        for (SlotChange c : changes) {
            if (c instanceof SlotChange.Added(var item, var pet)) {
                writeHeader(p, item, (byte) 0, (short) item.getPosition());
                // 可充值：wire 数量 = charge，经快照注入
                ItemSlot body = item.isRechargeable() ? chargedSnapshot(item) : item;
                PacketCreator.addItemInfo(p, body, true, pet);
            } else if (c instanceof SlotChange.QuantityUpdated(var item)) {
                writeHeader(p, item, (byte) 1, (short) item.getPosition());
                p.writeShort(wireQuantity(item));
            } else if (c instanceof SlotChange.Moved(var item, var oldPos)) {
                writeHeader(p, item, (byte) 2, oldPos);
                p.writeShort((short) item.getPosition());
                if (item.getPosition() < 0 || oldPos < 0) {
                    addMovement = oldPos < 0 ? 1 : 2;
                }
            } else if (c instanceof SlotChange.Removed(var item)) {
                writeHeader(p, item, (byte) 3, (short) item.getPosition());
                if (item.getPosition() < 0) {
                    addMovement = 2;
                }
            }
        }
        if (addMovement > -1) {
            p.writeByte(addMovement);
        }
        return p;
    }

    /** 帧头：mode + 背包类型 + 首短整（mode 2 为旧位置，其余为当前位置） */
    private static void writeHeader(OutPacket p, ItemSlot item, byte mode, short firstPos) {
        p.writeByte(mode);
        p.writeByte(item.getInventoryType().getType());
        p.writeShort(firstPos);
    }

    /** 可充值 wire 数量 = 可使用次数；经深拷贝快照注入，不改写共享槽位 */
    private static ItemSlot chargedSnapshot(ItemSlot item) {
        ItemSlot snapshot = item.copy();
        snapshot.setQuantity(item.getItem().getCharge());
        return snapshot;
    }

    private static short wireQuantity(ItemSlot item) {
        return (short) (item.isRechargeable() ? item.getItem().getCharge() : item.getQuantity());
    }
}
