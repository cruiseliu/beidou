package org.gms.remote.gms083.server.translators;

import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.remote.gms083.ServerTranslator;
import org.gms.remote.gms083.server.blocks.ItemBlock;
import org.gms.remote.gms083.server.events.FrozenInventoryEvent;
import org.gms.remote.gms083.server.packets.InventoryFullPacket;
import org.gms.remote.gms083.server.packets.InventoryOperationPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.modules.inventory.server.SlotChange;
import org.gms.remote.modules.pet.server.PetPanelEvent;
import org.gms.remote.modules.pet.server.PetSnap;

import java.util.ArrayList;
import java.util.List;

/**
 * 背包域翻译：语义 SlotChange → packet record（纯字段）。
 * 语义查表（可充值 wire 数量、cash 序列号、到期映射等）在 {@link ItemBlock} 工厂完成，
 * 本类只做变更形状翻译。职责见 doc/package-client.md §6。
 */public final class InventoryTranslator implements ServerTranslator {
    private final List<InventoryOperationPacket.Change> changes = new ArrayList<>();
    private boolean full = false;

    public void onInventoryMods(List<SlotChange> semantic) {
        for (SlotChange c : semantic) {
            changes.add(toChange(c));
        }
    }

    /** freeze 产物：pet 槽位已带快照的背包变更（Passthrough 原样 / PetBody 展开为 Add） */
    public void onFrozenInventory(FrozenInventoryEvent f) {
        for (FrozenInventoryEvent.Element el : f.elements()) {
            if (el instanceof FrozenInventoryEvent.Element.Passthrough(var c)) {
                changes.add(toChange(c));
            } else if (el instanceof FrozenInventoryEvent.Element.PetBody(short pos, int itemId, PetSnap snap)) {
                changes.add(new InventoryOperationPacket.Added((byte) InventoryType.CASH.getType(), pos,
                        ItemBlock.ofPet(itemId, snap.petId(), snap.name(), snap.level(), snap.tameness(),
                                snap.fullness(), snap.flags(), snap.alive(), snap.expiration())));
            }
        }
    }

    public void onInventoryFull() {
        full = true;
    }

    /**
     * 宠物面板快照 → body 刷新变更（Rem+Add，对齐 forceUpdateItem 帧序）。
     */
    public void onPetPanel(PetPanelEvent s) {
        byte tab = (byte) InventoryType.CASH.getType();
        changes.add(new InventoryOperationPacket.Removed(tab, s.pos()));
        changes.add(new InventoryOperationPacket.Added(tab, s.pos(),
                ItemBlock.ofPet(s.itemId(), s.petId(), s.name(), s.level(), s.tameness(),
                        s.fullness(), s.flags(), s.alive(), s.expiration())));
    }

    @Override
    public List<V83Packet> flush() {
        List<V83Packet> packets = new ArrayList<>(3);
        // 已生效变更先行；背包满=空操作帧 + 0xff 状态帧成对（对齐 addById 失败双包）
        if (!changes.isEmpty()) {
            packets.add(InventoryOperationPacket.of(true, changes));
            changes.clear();
        }
        if (full) {
            packets.add(InventoryOperationPacket.empty());
            packets.add(new InventoryFullPacket());
            full = false;
        }
        return packets;
    }

    // ── 语义 → packet 字段 ──

    private InventoryOperationPacket.Change toChange(SlotChange c) {
        if (c instanceof SlotChange.Added(var item, var pos, var quantity)) {
            return new InventoryOperationPacket.Added(
                    tabOf(item), (short) pos, ItemBlock.of(item, quantity));
        }
        if (c instanceof SlotChange.QuantityUpdated(var item, var pos, var quantity)) {
            short wireQuantity = (short) (item.isRechargeable() ? item.getCharge() : quantity);
            return new InventoryOperationPacket.QuantityUpdated(tabOf(item), (short) pos, wireQuantity);
        }
        if (c instanceof SlotChange.Moved(var item, var oldPos, var pos)) {
            return new InventoryOperationPacket.Moved(tabOf(item), (short) oldPos, (short) pos);
        }
        if (c instanceof SlotChange.Removed(var item, var pos)) {
            return new InventoryOperationPacket.Removed(tabOf(item), (short) pos);
        }
        throw new IllegalStateException("未知槽位变更: " + c);
    }

    private static byte tabOf(Item item) {
        return item.getInventoryTab().getType();
    }

}
