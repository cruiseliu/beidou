package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryProof;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ModifyInventory;
import org.gms.client.inventory.Pet;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.config.GameConfig;
import org.gms.constants.id.ItemId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.server.Server;
import org.gms.server.ItemInformationProvider;
import org.gms.server.TimerManager;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 背包模块组件：各类型背包（inventories）+ 槽位（slots/equipchanged）+ 物品查询/持有判定
 * + 物品过期（itemExpireTask）+ 沙盒物品 + 出售/扩容。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getInventory/canHold/... 对外转发）。
 *
 * 边界：只承载背包语义——物品存储、槽位、持有/容量判定、物品过期、出售。
 * 拾取流程（pickupItem）、装备合并（merge）、经验吊坠（pendantExp）等跨域编排留在 Character；
 * 持久化 SQL（inventoryitems/inventoryequipment、slots 列）留在 Character.saveCharToDB；
 * 依赖经 owner 门面调用（sendPacket/getMap/gainMeso/dropMessage/...）。
 */
class CharacterInventory {
    private final Character owner;

    /** 各类型背包（下标 = InventoryType.ordinal()） */
    Inventory[] inventories;

    private int slots = 0;
    /** 装备变化标志（CharacterStats.recalcEquipStats 读取） */
    boolean equipchanged = true;
    /** 沙盒物品标志 */
    private boolean hasSandboxItem = false;
    /** 物品过期扫描定时器 */
    private ScheduledFuture<?> itemExpireTask = null;
    /** 混沌卷轴（制造时使用） */
    private boolean useCS;

    CharacterInventory(Character owner) {
        this.owner = owner;
        useCS = false;
        inventories = new Inventory[InventoryType.values().length];

        for (InventoryType type : InventoryType.values()) {
            byte b = 24;
            if (type == InventoryType.CASH) {
                b = 96;
            }
            inventories[type.ordinal()] = new Inventory(owner, type, b);
        }
        inventories[InventoryType.CANHOLD.ordinal()] = new InventoryProof(owner);
    }

    // ── 查询 ──

    Inventory getInventory(InventoryType type) {
        return inventories[type.ordinal()];
    }

    /** 全部背包（saveCharToDB 遍历用；包内可见） */
    Inventory[] getInventories() {
        return inventories;
    }

    /** 释放全部背包（Character.empty 调用，防内存泄漏） */
    void disposeAll() {
        if (inventories != null) {
            for (Inventory inv : inventories) {
                if (inv != null) {
                    inv.dispose();
                }
            }
        }
        inventories = null;
    }

    int countItem(int itemid) {
        return inventories[ItemConstants.getInventoryType(itemid).ordinal()].countById(itemid);
    }

    boolean canHold(int itemid) {
        return canHold(itemid, 1);
    }

    boolean canHold(int itemid, int quantity) {
        return owner.client.getAbstractPlayerInteraction().canHold(itemid, quantity);
    }

    boolean canHoldUniques(List<Integer> itemids) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Integer itemid : itemids) {
            if (ii.isPickupRestricted(itemid) && this.haveItem(itemid)) {
                return false;
            }
        }

        return true;
    }

    boolean canHoldMeso(int gain) {  // thanks lucasziron for pointing out a need to check space availability for mesos on player transactions
        long nextMeso = (long) owner.getMeso() + gain;
        return nextMeso <= Integer.MAX_VALUE;
    }

    boolean haveItemWithId(int itemid, boolean checkEquipped) {
        return (inventories[ItemConstants.getInventoryType(itemid).ordinal()].findById(itemid) != null)
                || (checkEquipped && inventories[InventoryType.EQUIPPED.ordinal()].findById(itemid) != null);
    }

    boolean haveItemEquipped(int itemid) {
        return (inventories[InventoryType.EQUIPPED.ordinal()].findById(itemid) != null);
    }

    boolean haveWeddingRing() {
        int[] rings = {ItemId.WEDDING_RING_STAR, ItemId.WEDDING_RING_MOONSTONE, ItemId.WEDDING_RING_GOLDEN, ItemId.WEDDING_RING_SILVER};

        for (int ringid : rings) {
            if (haveItemWithId(ringid, true)) {
                return true;
            }
        }

        return false;
    }

    int getItemQuantity(int itemid, boolean checkEquipped) {
        int count = inventories[ItemConstants.getInventoryType(itemid).ordinal()].countById(itemid);
        if (checkEquipped) {
            count += inventories[InventoryType.EQUIPPED.ordinal()].countById(itemid);
        }
        return count;
    }

    int getCleanItemQuantity(int itemid, boolean checkEquipped) {
        int count = inventories[ItemConstants.getInventoryType(itemid).ordinal()].countNotOwnedById(itemid);
        if (checkEquipped) {
            count += inventories[InventoryType.EQUIPPED.ordinal()].countNotOwnedById(itemid);
        }
        return count;
    }

    boolean haveItem(int itemid) {
        return getItemQuantity(itemid, ItemConstants.isEquipment(itemid)) > 0;
    }

    boolean haveCleanItem(int itemid) {
        return getCleanItemQuantity(itemid, ItemConstants.isEquipment(itemid)) > 0;
    }

    boolean hasEmptySlot(int itemId) {
        return getInventory(ItemConstants.getInventoryType(itemId)).getNextFreeSlot() > -1;
    }

    boolean hasEmptySlot(byte invType) {
        return getInventory(InventoryType.getByType(invType)).getNextFreeSlot() > -1;
    }

    byte getSlots(int type) {
        return type == InventoryType.CASH.getType() ? 96 : inventories[type].getSlotLimit();
    }

    boolean canGainSlots(int type, int slots) {
        slots += inventories[type].getSlotLimit();
        return slots <= 96;
    }

    int getSlot() {
        return slots;
    }

    void setSlot(int slotid) {
        slots = slotid;
    }

    boolean isEquipChanged() {
        return equipchanged;
    }

    void setEquipChanged(boolean v) {
        equipchanged = v;
    }

    void setCS(boolean cs) {
        useCS = cs;
    }

    boolean isUseCS() {
        return useCS;
    }

    // ── 槽位扩容 ──

    boolean gainSlots(int type, int slots) {
        return gainSlots(type, slots, true);
    }

    boolean gainSlots(int type, int slots, boolean update) {
        int newLimit = gainSlotsInternal(type, slots);
        if (newLimit != -1) {
            owner.saveCharToDB();
            if (update) {
                owner.sendPacket(PacketCreator.updateInventorySlotLimit(type, newLimit));
            }
            return true;
        } else {
            return false;
        }
    }

    private int gainSlotsInternal(int type, int slots) {
        inventories[type].lockInventory();
        try {
            if (canGainSlots(type, slots)) {
                int newLimit = inventories[type].getSlotLimit() + slots;
                inventories[type].setSlotLimit(newLimit);
                return newLimit;
            } else {
                return -1;
            }
        } finally {
            inventories[type].unlockInventory();
        }
    }

    // ── 物品过期 ──

    void cancelExpirationTask() {
        if (itemExpireTask != null) {
            itemExpireTask.cancel(false);
            itemExpireTask = null;
        }
    }

    void expirationTask() {
        if (itemExpireTask == null) {
            itemExpireTask = TimerManager.getInstance().register(() -> {
                boolean deletedCoupon = false;

                long expiration, currenttime = System.currentTimeMillis();

                List<Item> toberemove = new ArrayList<>();
                for (Inventory inv : inventories) {
                    for (Item item : inv.list()) {
                        expiration = item.getExpiration();

                        if (expiration != -1 && (expiration < currenttime) && ((item.getFlag() & ItemConstants.LOCK) == ItemConstants.LOCK)) {
                            short lock = item.getFlag();
                            lock &= ~(ItemConstants.LOCK);
                            item.setFlag(lock); //Probably need a check, else people can make expiring items into permanent items...
                            item.setExpiration(-1);
                            forceUpdateItem(item);   //TEST :3
                        } else if (expiration != -1 && expiration < currenttime) {
                            if (!ItemConstants.isPet(item.getItemId())) {
                                owner.sendPacket(PacketCreator.itemExpired(item.getItemId()));
                                toberemove.add(item);
                                if (ItemConstants.isRateCoupon(item.getItemId())) {
                                    deletedCoupon = true;
                                }
                            } else {
                                Pet pet = item.getPet();   // thanks Lame for noticing pets not getting despawned after expiration time
                                if (pet != null) {
                                    owner.unEquipPet(pet, true);
                                }

                                if (ItemConstants.isExpirablePet(item.getItemId())) {
                                    if (item.getPetId() > -1) {
                                        // 宠物道具真正过期销毁时，同时清理 pets/petignores，避免数据库残留孤儿数据。
                                        Pet.deleteFromDb(owner, item.getPetId());
                                    }
                                    owner.sendPacket(PacketCreator.itemExpired(item.getItemId()));
                                    toberemove.add(item);
                                } else {
                                    item.setExpiration(-1);
                                    forceUpdateItem(item);
                                }
                            }
                        }
                    }

                    if (!toberemove.isEmpty()) {
                        for (Item item : toberemove) {
                            InventoryManipulator.removeFromSlot(owner.client, inv.getType(), item.getPosition(), item.getQuantity(), true);
                        }

                        ItemInformationProvider ii = ItemInformationProvider.getInstance();
                        for (Item item : toberemove) {
                            List<Integer> toadd = new ArrayList<>();
                            Pair<Integer, String> replace = ii.getReplaceOnExpire(item.getItemId());
                            if (replace.left > 0) {
                                toadd.add(replace.left);
                                if (!replace.right.isEmpty()) {
                                    owner.dropMessage(replace.right);
                                }
                            }
                            for (Integer itemid : toadd) {
                                InventoryManipulator.addById(owner.client, itemid, (short) 1);
                            }
                        }

                        toberemove.clear();
                    }

                    if (deletedCoupon) {
                        owner.updateCouponRates();
                    }
                }
            }, 60000);
        }
    }

    // ── 物品操作 ──

    void forceUpdateItem(Item item) {
        final List<ModifyInventory> mods = new ArrayList<>();
        mods.add(new ModifyInventory(3, item));
        mods.add(new ModifyInventory(0, item));
        owner.sendPacket(PacketCreator.modifyInventory(true, mods));
    }

    void setHasSandboxItem() {
        hasSandboxItem = true;
    }

    void removeSandboxItems() {  // sandbox idea thanks to Morty
        if (!hasSandboxItem) {
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (InventoryType invType : InventoryType.values()) {
            Inventory inv = this.getInventory(invType);

            inv.lockInventory();
            try {
                for (Item item : new ArrayList<>(inv.list())) {
                    if (InventoryManipulator.isSandboxItem(item)) {
                        InventoryManipulator.removeFromSlot(owner.client, invType, item.getPosition(), item.getQuantity(), false);
                        owner.dropMessage(5, "[" + ii.getName(item.getItemId()) + "] " + I18nUtil.getMessage("Character.removeSandboxItems.message1"));
                    }
                }
            } finally {
                inv.unlockInventory();
            }
        }

        hasSandboxItem = false;
    }

    void equipChanged() {
        owner.getMap().broadcastUpdateCharLookMessage(owner, owner);
        equipchanged = true;
        owner.stats.updateLocalStats();
        if (owner.getMessenger() != null) {
            owner.getWorldServer().updateMessenger(owner.getMessenger(), owner.getName(), owner.getWorld(), owner.client.getChannel());
        }
    }

    // ── 出售 ──

    int sellAllItemsFromName(byte invTypeId, String name) {
        //player decides from which inventory items should be sold.
        InventoryType type = InventoryType.getByType(invTypeId);

        Inventory inv = getInventory(type);
        inv.lockInventory();
        try {
            Item it = inv.findByName(name);
            if (it == null) {
                return (-1);
            }

            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return (sellAllItemsFromPosition(ii, type, it.getPosition()));
        } finally {
            inv.unlockInventory();
        }
    }

    int sellAllItemsFromPosition(ItemInformationProvider ii, InventoryType type, short pos) {
        int mesoGain = 0;

        Inventory inv = getInventory(type);
        inv.lockInventory();
        try {
            for (short i = pos; i <= inv.getSlotLimit(); i++) {
                if (inv.getItem(i) == null) {
                    continue;
                }
                mesoGain += standaloneSell(owner.getClient(), ii, type, i, inv.getItem(i).getQuantity());
            }
        } finally {
            inv.unlockInventory();
        }

        return (mesoGain);
    }

    private int standaloneSell(Client c, ItemInformationProvider ii, InventoryType type, short slot, short quantity) {
        if (quantity == 0) {
            quantity = 1;
        }

        Inventory inv = getInventory(type);
        inv.lockInventory();
        try {
            Item item = inv.getItem(slot);
            if (item == null) { //Basic check
                return (0);
            }

            int itemid = item.getItemId();
            if (ItemConstants.isRechargeable(itemid)) {
                quantity = item.getQuantity();
            } else if (ItemId.isWeddingToken(itemid) || ItemId.isWeddingRing(itemid)) {
                return (0);
            }

            if (quantity < 0) {
                return (0);
            }
            short iQuant = item.getQuantity();

            if (quantity <= iQuant && iQuant > 0) {
                InventoryManipulator.removeFromSlot(c, type, (byte) slot, quantity, false);
                int recvMesos = ii.getPrice(itemid, quantity);
                if (recvMesos > 0) {
                    owner.gainMeso(recvMesos, false);
                    return (recvMesos);
                }
            }

            return (0);
        } finally {
            inv.unlockInventory();
        }
    }
}
