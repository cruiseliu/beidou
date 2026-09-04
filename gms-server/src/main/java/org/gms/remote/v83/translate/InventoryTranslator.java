package org.gms.remote.v83.translate;

import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.game.ExpTable;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.remote.SemanticEvent;
import org.gms.remote.PetSnap;
import org.gms.remote.SlotChange;
import org.gms.remote.v83.FrozenInventoryEvent;
import org.gms.remote.v83.packet.InventoryFullPacket;
import org.gms.remote.v83.packet.InventoryOperationPacket;
import org.gms.remote.v83.packet.V83Packet;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 背包域翻译：语义 SlotChange → packet record（纯字段）。
 * 语义查表在此完成：可充值 wire 数量、cash 序列号三选一（宠物=petId、戒指=ringId、
 * 其余=cashId）、成长经验 nibble（ExpTable）、到期映射。职责见 doc/package-client.md §6。
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

    /** freeze 产物：pet 槽位已带快照的背包变更（Passthrough 原样 / PetBody 展开为 Add） */
    public void onFrozenInventory(FrozenInventoryEvent f) {
        for (FrozenInventoryEvent.Element el : f.elements()) {
            if (el instanceof FrozenInventoryEvent.Element.Passthrough(var c)) {
                changes.add(toChange(c));
            } else if (el instanceof FrozenInventoryEvent.Element.PetBody(short pos, int itemId, PetSnap snap)) {
                changes.add(new InventoryOperationPacket.Added((byte) InventoryType.CASH.getType(), pos,
                        petBody(itemId, snap.petId(), snap.name(), snap.level(), snap.tameness(),
                                snap.fullness(), snap.flags(), snap.alive(), snap.expiration())));
            }
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
            // 可充值 wire 数量 = 可使用次数（charge）；语义 quantity 为组数（恒 1）
            short wireQuantity = (short) (item.isRechargeable() ? item.getCharge() : quantity);
            return new InventoryOperationPacket.Added(
                    tabOf(item), (short) pos, bodyOf(item, wireQuantity));
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

    /** Item 实体 + wire 数量 → 纯字段物品体（Equip 域查表在此完成） */
    private InventoryOperationPacket.ItemBody bodyOf(Item item, int wireQuantity) {
        int itemId = item.getItemId();
        boolean cash = ii.isCash(itemId);
        long expiration = Filetimes.toWire(item.getExpiration());
        byte type = (byte) item.getItemType();

        if (type == 3) {
            // 不变量：宠物槽位的 Added 必经 FrozenInventoryEvent.PetBody（freeze 保证），不该走到这里
            throw new IllegalStateException("宠物物品体缺冻结快照: " + itemId);
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
                (short) wireQuantity, item.getOwner(), item.getLegacyFlags(),
                ItemConstants.isRechargeable(itemId));
    }

    /**
     * 宠物面板快照 → body 刷新变更（Rem+Add，对齐 forceUpdateItem 帧序）。
     * tameness 在本层 min(30000) 截断：服务端可持有超出值，客户端只显示 30000。
     */
    public void onPetPanel(SemanticEvent.PetPanel s) {
        byte tab = (byte) InventoryType.CASH.getType();
        changes.add(new InventoryOperationPacket.Removed(tab, s.pos()));
        changes.add(new InventoryOperationPacket.Added(tab, s.pos(),
                petBody(s.itemId(), s.petId(), s.name(), s.level(), s.tameness(),
                        s.fullness(), s.flags(), s.alive(), s.expiration())));
    }

    /** 宠物物品体（PetModule 面板快照与 inventory SlotChange 共用）。
     *  宠物到期归 Pet（item.expiration 恒 -1）；客户端语义（实测）：wire ≥ EXPIRED
     *  显示"过期"，PERMANENT 显示"永久"，其余显示日期。 */
    private InventoryOperationPacket.ItemBody.Pet petBody(int itemId, long petId, String name,
            int level, int tameness, int fullness, int flags, boolean alive, long expiration) {
        long wireExpiration;
        if (!alive) {
            wireExpiration = Filetimes.EXPIRED;              // 失活 → "过期"
        } else if (expiration == -1) {
            wireExpiration = Filetimes.PERMANENT;            // 永久 → "永久"
        } else {
            wireExpiration = Filetimes.toWire(expiration);
        }
        return new InventoryOperationPacket.ItemBody.Pet(
                itemId, true, petId, wireExpiration,
                name.getBytes(charset),
                (byte) level, (short) Math.min(tameness, 30000), (byte) fullness,
                (short) flags);
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

    private static byte tabOf(Item item) {
        return item.getInventoryTab().getType();
    }

}
