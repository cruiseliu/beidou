package org.gms.remote.v83.translate;

import io.netty.buffer.ByteBuf;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.Pet;
import org.gms.constants.game.ExpTable;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.remote.SlotChange;
import org.gms.remote.v83.packet.InventoryFullPacket;
import org.gms.remote.v83.packet.InventoryOperationPacket;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 背包域翻译：语义 SlotChange → packet 树（纯字段）。
 * 到达即翻译（冻结语义）：可充值物品的 wire 数量（= 可使用次数）在本层经快照拷贝
 * 抽取，不触碰共享槽位；cash 序列号三选一（宠物=petId、戒指=ringId、其余=cashId）、
 * 成长经验 nibble（ExpTable）等语义查表也在此完成——packet 层只见基础字段。
 * 背包满提示与操作包可能同帧产出（两个 ByteBuf）。
 */
public final class InventoryTranslator implements Translator {
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    private final Charset charset;
    private final List<InventoryOperationPacket.Change> changes = new ArrayList<>();
    private boolean full = false;

    public InventoryTranslator(Charset charset) {
        this.charset = charset;
    }

    public void onInventoryMods(List<SlotChange> semantic) {
        for (SlotChange c : semantic) {
            changes.add(toChange(c));
        }
    }

    public void onInventoryFull() {
        full = true;
    }

    @Override
    public boolean isEmpty() {
        return changes.isEmpty() && !full;
    }

    @Override
    public List<ByteBuf> flush() {
        List<ByteBuf> frames = new ArrayList<>(2);
        if (full) {
            frames.add(InventoryFullPacket.encode());
            full = false;
        }
        if (!changes.isEmpty()) {
            frames.add(InventoryOperationPacket.encode(
                    InventoryOperationPacket.of(true, changes)));
            changes.clear();
        }
        return frames;
    }

    // ── 语义 → packet 字段 ──

    private InventoryOperationPacket.Change toChange(SlotChange c) {
        if (c instanceof SlotChange.Added(var item, var pet)) {
            ItemSlot snapshot = item.isRechargeable() ? chargedSnapshot(item) : item;
            return new InventoryOperationPacket.Added(
                    tabOf(item), (short) item.getPosition(), bodyOf(snapshot, pet));
        }
        if (c instanceof SlotChange.QuantityUpdated(var item)) {
            return new InventoryOperationPacket.QuantityUpdated(
                    tabOf(item), (short) item.getPosition(),
                    (short) (item.isRechargeable() ? item.getItem().getCharge() : item.getQuantity()));
        }
        if (c instanceof SlotChange.Moved(var item, var oldPos)) {
            return new InventoryOperationPacket.Moved(
                    tabOf(item), oldPos, (short) item.getPosition());
        }
        if (c instanceof SlotChange.Removed(var item)) {
            return new InventoryOperationPacket.Removed(tabOf(item), (short) item.getPosition());
        }
        throw new IllegalStateException("未知槽位变更: " + c);
    }

    /** 语义快照（可充值：数量 = charge）→ 纯字段物品体 */
    private InventoryOperationPacket.ItemBody bodyOf(ItemSlot item, Pet pet) {
        int itemId = item.getItemId();
        boolean cash = ii.isCash(itemId);
        long expiration = Filetimes.toWire(item.getExpiration());
        byte type = (byte) item.getItemType();

        if (type == 3) {
            if (pet == null) {
                throw new IllegalArgumentException("宠物物品缺 Pet 对象: " + itemId);
            }
            return new InventoryOperationPacket.ItemBody.Pet(
                    itemId, cash, cash ? item.getPetId() : 0, expiration,
                    pet.getName().getBytes(charset),
                    (byte) pet.getLevel(), (short) pet.getTameness(), (byte) pet.getFullness(),
                    (short) pet.getPetAttribute());
        }
        long serial = 0;
        if (cash && item.getCashInfo() != null) {
            serial = item.getCashInfo().getCashId();
        }
        if (type == 1) {
            Equip equip = item.getEquipInfo();
            if (cash && equip.getRingId() > -1) {
                serial = equip.getRingId();
            }
            var levelInfo = cash
                    ? new InventoryOperationPacket.LevelInfo.CashPadding()
                    : new InventoryOperationPacket.LevelInfo.Growth((byte) 0,
                            (byte) equip.getItemLevel(),
                            (int) (ExpTable.getExpNeededForLevel(ii.getEquipLevelReq(itemId)) * equip.getItemExp()
                                    / ExpTable.getExpNeededForLevel(equip.getItemLevel())),
                            equip.getVicious(), 0L);
            return new InventoryOperationPacket.ItemBody.Equip(
                    itemId, cash, serial, expiration,
                    (byte) equip.getEnhancementSlots(), (byte) equip.getEnhancementLevel(),
                    equipStats(equip), item.getOwner(), item.getLegacyFlags(),
                    levelInfo, Filetimes.toWire(-2), -1);
        }
        return new InventoryOperationPacket.ItemBody.Stack(
                itemId, cash, serial, expiration,
                (short) item.getQuantity(), item.getOwner(), item.getLegacyFlags(),
                ItemConstants.isRechargeable(itemId));
    }

    private short[] equipStats(Equip equip) {
        return new short[]{
                (short) equip.getStat(org.gms.client.character.Stat.STR),
                (short) equip.getStat(org.gms.client.character.Stat.DEX),
                (short) equip.getStat(org.gms.client.character.Stat.INT),
                (short) equip.getStat(org.gms.client.character.Stat.LUK),
                (short) equip.getStat(org.gms.client.character.Stat.MAX_HP),
                (short) equip.getStat(org.gms.client.character.Stat.MAX_MP),
                (short) equip.getStat(org.gms.client.character.Stat.P_ATK),
                (short) equip.getStat(org.gms.client.character.Stat.M_ATK),
                (short) equip.getStat(org.gms.client.character.Stat.P_DEF),
                (short) equip.getStat(org.gms.client.character.Stat.M_DEF),
                (short) equip.getStat(org.gms.client.character.Stat.ACCURACY),
                (short) equip.getStat(org.gms.client.character.Stat.AVOIDABILITY),
                (short) equip.getStat(org.gms.client.character.Stat.HANDS),
                (short) equip.getStat(org.gms.client.character.Stat.SPEED),
                (short) equip.getStat(org.gms.client.character.Stat.JUMP),
        };
    }

    private static byte tabOf(ItemSlot item) {
        return item.getInventoryType().getType();
    }

    private static ItemSlot chargedSnapshot(ItemSlot item) {
        ItemSlot snapshot = item.copy();
        snapshot.setQuantity(item.getItem().getCharge());
        return snapshot;
    }
}
