package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryProof;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ModifyInventory;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Equip.StatUpgrade;
import org.gms.server.CashShop;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.scripting.item.ItemScriptManager;
import org.gms.util.RequireUtil;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MINUTES;
import org.gms.client.inventory.Pet;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.config.GameConfig;
import org.gms.constants.id.ItemId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.constants.id.MapId;
import org.gms.net.packet.Packet;
import org.gms.server.ItemInformationProvider.ScriptedItem;
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
        owner.stats.recalcAndSyncParty();
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

    // ── 拾取 ──

    void pickupItem(MapObject ob) {
        pickupItem(ob, -1);
    }


    // ── 装备合并与强化 ──

    void pickupItem(MapObject ob, int petIndex) {     // yes, one picks the MapObject, not the MapItem     //是的，选择MapObject，而不是MapItem
        if (ob == null) {                                               // pet index refers to the one picking up the item      //宠物指数是指捡起物品的人
            return;
        }

        if (ob instanceof MapItem mapitem) {
            if (System.currentTimeMillis() - mapitem.getDropTime() < 400) {
                owner.enableActions();
                return;
            }

            // canBePickedBy 读/写 owner 字段,必须持 itemLock
            mapitem.lockItem();
            try {
                if (!mapitem.canBePickedBy(owner)) {
                    owner.enableActions();
                    return;
                }
            } finally {
                mapitem.unlockItem();
            }

            List<Character> mpcs = new LinkedList<>();
            if (mapitem.getMeso() > 0 && !mapitem.isPickedUp()) {
                mpcs = owner.getPartyMembersOnSameMap();
            }

            ScriptedItem itemScript = null;
            mapitem.lockItem();
            try {
                if (mapitem.isPickedUp()) {
                    owner.sendPacket(PacketCreator.showItemUnavailable());
                    owner.enableActions();
                    return;
                }

                boolean isPet = petIndex > -1;
                final Packet pickupPacket = PacketCreator.removeItemFromMap(mapitem.getObjectId(), (isPet) ? 5 : 2, owner.getId(), isPet, petIndex);

                Item mItem = mapitem.getItem();
                boolean hasSpaceInventory = true;
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                if (ItemId.isNxCard(mapitem.getItemId()) || mapitem.getMeso() > 0 || ii.isConsumeOnPickup(mapitem.getItemId()) || (hasSpaceInventory = InventoryManipulator.checkSpace(owner.client, mapitem.getItemId(), mItem.getQuantity(), mItem.getOwner()))) {
                    int mapId = owner.getMapId();

                    if ((MapId.isSelfLootableOnly(mapId))) {//happyville trees and guild PQ
                        if (!mapitem.isPlayerDrop() || mapitem.getDropper().getObjectId() == owner.client.getPlayer().getObjectId()) {
                            if (mapitem.getMeso() > 0) {
                                if (!mpcs.isEmpty()) {
                                    int mesosamm = mapitem.getMeso() / mpcs.size();
                                    for (Character partymem : mpcs) {
                                        if (partymem.isLoggedInWorld()) {
                                            partymem.gainMeso(mesosamm, true, true, false);
                                        }
                                    }
                                } else {
                                    owner.gainMeso(mapitem.getMeso(), true, true, false);
                                }

                                owner.getMap().pickItemDrop(pickupPacket, mapitem);
                            } else if (ItemId.isNxCard(mapitem.getItemId())) {
                                // Add NX to account, show effect and make item disappear   //添加点券到账户，是否展示捡到点券，并移除物品
                                int nxGain = (mapitem.getItemId() == ItemId.NX_CARD_100 ? 100 : 250) * mItem.getQuantity(); //使点券支持按数量相乘
                                owner.getCashShop().gainCash(CashShop.NX_CREDIT, nxGain);

                                if (GameConfig.getServerBoolean("use_announce_nx_coupon_loot")) {       //捡到点券是否展示
                                    owner.showHint(I18nUtil.getMessage("Character.pickupItem.message1", nxGain, owner.getCashShop().getCash(CashShop.NX_CREDIT)), 300);
                                    //owner.showHint("捡到 #e#b" + nxGain + " NX#k#n (" + owner.getCashShop().getCash(CashShop.NX_CREDIT) + " NX)", 300);
                                }

                                owner.getMap().pickItemDrop(pickupPacket, mapitem);
                            } else if (InventoryManipulator.addFromDrop(owner.client, mItem, true)) {
                                owner.getMap().pickItemDrop(pickupPacket, mapitem);
                            } else {
                                owner.enableActions();
                                return;
                            }
                        } else {
                            owner.sendPacket(PacketCreator.showItemUnavailable());
                            owner.enableActions();
                            return;
                        }
                        owner.enableActions();
                        return;
                    }

                    if (!owner.quests.needQuestItem(mapitem.getQuest(), mapitem.getItemId())) {
                        owner.sendPacket(PacketCreator.showItemUnavailable());
                        owner.enableActions();
                        return;
                    }

                    if (mapitem.getMeso() > 0) {
                        if (!mpcs.isEmpty()) {
                            int mesosamm = mapitem.getMeso() / mpcs.size();
                            for (Character partymem : mpcs) {
                                if (partymem.isLoggedInWorld()) {
                                    partymem.gainMeso(mesosamm, true, true, false);
                                }
                            }
                        } else {
                            owner.gainMeso(mapitem.getMeso(), true, true, false);
                        }
                    } else if (mItem.getItemId() / 10000 == 243) {
                        ScriptedItem info = ii.getScriptedItemInfo(mItem.getItemId());
                        if (info != null && info.runOnPickup()) {
                            itemScript = info;
                        } else {
                            if (!InventoryManipulator.addFromDrop(owner.client, mItem, true)) {
                                owner.enableActions();
                                return;
                            }
                        }
                    } else if (ItemId.isNxCard(mapitem.getItemId())) {
                        // Add NX to account, show effect and make item disappear
                        int nxGain = (mapitem.getItemId() == ItemId.NX_CARD_100 ? 100 : 250) * mItem.getQuantity(); //使点券支持按数量相乘
                        owner.getCashShop().gainCash(CashShop.NX_CREDIT, nxGain);

                        if (GameConfig.getServerBoolean("use_announce_nx_coupon_loot")) {       //捡到点券是否展示
                            owner.showHint(I18nUtil.getMessage("Character.pickupItem.message1", nxGain, owner.getCashShop().getCash(CashShop.NX_CREDIT)), 300);
                            //owner.showHint("捡到 #e#b" + nxGain + " NX#k#n (" + owner.getCashShop().getCash(CashShop.NX_CREDIT) + " NX)", 300);
                        }
                    } else if (owner.applyConsumeOnPickup(mItem.getItemId())) {//此段判断为处理捡取治疗道具和怪物卡加入图鉴
                    } else if (InventoryManipulator.addFromDrop(owner.client, mItem, true)) {
                        if (mItem.getItemId() == ItemId.ARPQ_SPIRIT_JEWEL) {
                            owner.pq.updateAriantScore();
                        }
                    } else {
                        owner.enableActions();
                        return;
                    }

                    owner.getMap().pickItemDrop(pickupPacket, mapitem);
                } else if (!hasSpaceInventory) {
                    owner.sendPacket(PacketCreator.getInventoryFull());
                    owner.sendPacket(PacketCreator.getShowInventoryFull());
                }
            } finally {
                mapitem.unlockItem();
            }

            if (itemScript != null) {
                ItemScriptManager ism = ItemScriptManager.getInstance();
                ism.runItemScript(owner.client, itemScript);
            }
        }
        owner.enableActions();
    }

    List<Equip> getUpgradeableEquipped() {
        List<Equip> list = new LinkedList<>();

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item item : getInventory(InventoryType.EQUIPPED)) {
            if (ii.isUpgradeable(item.getItemId())) {
                list.add((Equip) item);
            }
        }

        return list;
    }

    static List<Equip> getEquipsWithStat(List<Pair<Equip, Map<StatUpgrade, Short>>> equipped, StatUpgrade stat) {
        List<Equip> equippedWithStat = new LinkedList<>();

        for (Pair<Equip, Map<StatUpgrade, Short>> eq : equipped) {
            if (eq.getRight().containsKey(stat)) {
                equippedWithStat.add(eq.getLeft());
            }
        }

        return equippedWithStat;
    }

    boolean mergeAllItemsFromName(String name) {
        InventoryType type = InventoryType.EQUIP;

        Inventory inv = getInventory(type);
        inv.lockInventory();
        try {
            Item it = inv.findByName(name);
            if (it == null) {
                return false;
            }

            Map<StatUpgrade, Float> statups = new LinkedHashMap<>();
            mergeAllItemsFromPosition(statups, it.getPosition());

            List<Pair<Equip, Map<StatUpgrade, Short>>> upgradeableEquipped = new LinkedList<>();
            Map<Equip, List<Pair<StatUpgrade, Integer>>> equipUpgrades = new LinkedHashMap<>();
            for (Equip eq : getUpgradeableEquipped()) {
                upgradeableEquipped.add(new Pair<>(eq, eq.getStats()));
                equipUpgrades.put(eq, new LinkedList<Pair<StatUpgrade, Integer>>());
            }

            /*
            for (Entry<StatUpgrade, Float> es : statups.entrySet()) {
                System.out.println(es);
            }
            */

            for (Entry<StatUpgrade, Float> e : statups.entrySet()) {
                Double ev = Math.sqrt(e.getValue());

                Set<Equip> extraEquipped = new LinkedHashSet<>(equipUpgrades.keySet());
                List<Equip> statEquipped = getEquipsWithStat(upgradeableEquipped, e.getKey());
                float extraRate = (float) (0.2 * Math.random());

                if (!statEquipped.isEmpty()) {
                    float statRate = 1.0f - extraRate;

                    int statup = (int) Math.ceil((ev * statRate) / statEquipped.size());
                    for (Equip statEq : statEquipped) {
                        equipUpgrades.get(statEq).add(new Pair<>(e.getKey(), statup));
                        extraEquipped.remove(statEq);
                    }
                }

                if (!extraEquipped.isEmpty()) {
                    int statup = (int) Math.round((ev * extraRate) / extraEquipped.size());
                    if (statup > 0) {
                        for (Equip extraEq : extraEquipped) {
                            equipUpgrades.get(extraEq).add(new Pair<>(e.getKey(), statup));
                        }
                    }
                }
            }

            owner.dropMessage(6, "EQUIPMENT MERGE operation results:");
            for (Entry<Equip, List<Pair<StatUpgrade, Integer>>> eqpUpg : equipUpgrades.entrySet()) {
                List<Pair<StatUpgrade, Integer>> eqpStatups = eqpUpg.getValue();
                if (!eqpStatups.isEmpty()) {
                    Equip eqp = eqpUpg.getKey();
                    setMergeFlag(eqp);

                    String showStr = " '" + ItemInformationProvider.getInstance().getName(eqp.getItemId()) + "': ";
                    String upgdStr = eqp.gainStats(eqpStatups).getLeft();

                    owner.forceUpdateItem(eqp);

                    showStr += upgdStr;
                    owner.dropMessage(6, showStr);
                }
            }

            return true;
        } finally {
            inv.unlockInventory();
        }
    }

    void mergeAllItemsFromPosition(Map<StatUpgrade, Float> statUps, short pos) {
        Inventory inv = getInventory(InventoryType.EQUIP);
        inv.lockInventory();
        try {
            for (short i = pos; i <= inv.getSlotLimit(); i++) {
                standaloneMerge(statUps, owner.getClient(), InventoryType.EQUIP, i, inv.getItem(i));
            }
        } finally {
            inv.unlockInventory();
        }
    }

    void standaloneMerge(Map<StatUpgrade, Float> statUps, Client c, InventoryType type, short slot, Item item) {
        short quantity;
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (item == null || (quantity = item.getQuantity()) < 1 || ii.isCash(item.getItemId()) || !ii.isUpgradeable(item.getItemId()) || hasMergeFlag(item)) {
            return;
        }

        Equip e = (Equip) item;
        for (Entry<StatUpgrade, Short> s : e.getStats().entrySet()) {
            Float newVal = statUps.get(s.getKey());

            float incVal = s.getValue().floatValue();
            incVal = switch (s.getKey()) {
                case incPAD, incMAD, incPDD, incMDD -> (float) Math.log(incVal);
                default -> incVal;
            };

            if (newVal != null) {
                newVal += incVal;
            } else {
                newVal = incVal;
            }

            statUps.put(s.getKey(), newVal);
        }

        InventoryManipulator.removeFromSlot(c, type, (byte) slot, quantity, false);
    }

    Collection<Item> getUpgradeableEquipList() {
        Collection<Item> fullList = getInventory(InventoryType.EQUIPPED).list();
        if (GameConfig.getServerBoolean("use_equipment_level_up_cash")) {
            return fullList;
        }

        Collection<Item> eqpList = new LinkedHashSet<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item it : fullList) {
            if (!ii.isCash(it.getItemId())) {
                eqpList.add(it);
            }
        }

        return eqpList;
    }


    private static boolean hasMergeFlag(Item item) {
        return (item.getFlag() & ItemConstants.MERGE_UNTRADEABLE) == ItemConstants.MERGE_UNTRADEABLE;
    }

    private static void setMergeFlag(Item item) {
        short flag = item.getFlag();
        flag |= ItemConstants.MERGE_UNTRADEABLE;
        flag |= ItemConstants.UNTRADEABLE;
        item.setFlag(flag);
    }
    void increaseEquipExp(int expGain) {
        if (owner.allowExpGain) {     // thanks Vcoc for suggesting equip EXP gain conditionally
            if (expGain < 0) {
                expGain = Integer.MAX_VALUE;
            }

            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            for (Item item : getUpgradeableEquipList()) {
                Equip nEquip = (Equip) item;
                String itemName = ii.getName(nEquip.getItemId());
                if (itemName == null) {
                    continue;
                }

                nEquip.gainItemExp(owner.client, expGain);
            }
        }
    }

    void showAllEquipFeatures() {
        StringBuilder showMsg = new StringBuilder();

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item item : getInventory(InventoryType.EQUIPPED).list()) {
            Equip nEquip = (Equip) item;
            String itemName = ii.getName(nEquip.getItemId());
            if (itemName == null) {
                continue;
            }

            showMsg.append(nEquip.showEquipFeatures(owner.client));
        }

        if (!showMsg.isEmpty()) {
            owner.showHint("#ePLAYER EQUIPMENTS:#n\r\n\r\n" + showMsg, 400);
        }
    }

    /**
     * 发装备，除id外都可以传null，传null取装备默认属性
     *
     * @param itemId      装备id
     * @param attStr      力量
     * @param attDex      敏捷
     * @param attInt      智力
     * @param attLuk      运气
     * @param attHp       血量
     * @param attMp       蓝量
     * @param pAtk        物理攻击
     * @param mAtk        魔法攻击
     * @param pDef        物理防御
     * @param mDef        魔法防御
     * @param acc         命中
     * @param avoid       回避
     * @param hands       攻击速度
     * @param speed       移动速度
     * @param jump        跳跃
     * @param upgradeSlot 可升级次数
     * @param expireTime  失效时间，-1为不失效 来自 @leevccc 的建议，传值则为分钟
     */
    void gainEquip(int itemId, Short attStr, Short attDex, Short attInt, Short attLuk, Short attHp, Short attMp,
                          Short pAtk, Short mAtk, Short pDef, Short mDef, Short acc, Short avoid, Short hands, Short speed,
                          Short jump, Byte upgradeSlot, Long expireTime) {
        if (!ItemConstants.getInventoryType(itemId).equals(InventoryType.EQUIP)) {
            owner.message(I18nUtil.getMessage("AbstractPlayerInteraction.gainEquip.message1"));
            return;
        }
        Equip baseEquip = (Equip) ItemInformationProvider.getInstance().getEquipById(itemId);
        baseEquip.setQuantity((short) 1);
        if (!InventoryManipulator.checkSpace(owner.getClient(), itemId, 1, baseEquip.getOwner())) {
            owner.message(I18nUtil.getMessage("AbstractPlayerInteraction.gainEquip.message2", InventoryType.EQUIP.getName()));
        }
        RequireUtil.requireNotEmptyAndThen(baseEquip, attStr, Equip::setStr);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attDex, Equip::setDex);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attInt, Equip::setInt);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attLuk, Equip::setLuk);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attHp, Equip::setHp);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attMp, Equip::setMp);
        RequireUtil.requireNotEmptyAndThen(baseEquip, pAtk, Equip::setWatk);
        RequireUtil.requireNotEmptyAndThen(baseEquip, mAtk, Equip::setMatk);
        RequireUtil.requireNotEmptyAndThen(baseEquip, pDef, Equip::setWdef);
        RequireUtil.requireNotEmptyAndThen(baseEquip, mDef, Equip::setMdef);
        RequireUtil.requireNotEmptyAndThen(baseEquip, acc, Equip::setAcc);
        RequireUtil.requireNotEmptyAndThen(baseEquip, avoid, Equip::setAvoid);
        RequireUtil.requireNotEmptyAndThen(baseEquip, hands, Equip::setHands);
        RequireUtil.requireNotEmptyAndThen(baseEquip, speed, Equip::setSpeed);
        RequireUtil.requireNotEmptyAndThen(baseEquip, jump, Equip::setJump);
        RequireUtil.requireNotEmptyAndThen(baseEquip, upgradeSlot, Equip::setUpgradeSlots);
        RequireUtil.requireNotEmptyAndThen(baseEquip, expireTime, (eq, ep) -> {
            if (ep > 0) {
                eq.setExpiration(MINUTES.toMillis(ep) + System.currentTimeMillis());
            } else {
                eq.setExpiration(-1);
            }
        });
        InventoryManipulator.addFromDrop(owner.getClient(), baseEquip, false);
    }

    // ── 精灵吊坠（装备域） ──

    private ScheduledFuture<?> pendantOfSpirit = null; //1122017

    void equippedItem(Equip equip) {
        int itemid = equip.getItemId();

        if (itemid == ItemId.PENDANT_OF_THE_SPIRIT) {
            this.equipPendantOfSpirit();
        }
    }

    void unequippedItem(Equip equip) {
        int itemid = equip.getItemId();

        if (itemid == ItemId.PENDANT_OF_THE_SPIRIT) {
            this.unequipPendantOfSpirit();
        }
    }

    private void equipPendantOfSpirit() {   //精灵吊坠装备时长经验计算
        if (pendantOfSpirit == null) {
            pendantOfSpirit = TimerManager.getInstance().register(() -> {
                if (owner.pendantExp < 3) {
                    owner.pendantExp++;
                    //用于准确提示装备1小时内还是装备经过几小时
                    owner.message(I18nUtil.getMessage(owner.pendantExp <= 2 ? "Character.equipPendantOfSpirit.message1" : "Character.equipPendantOfSpirit.message2", owner.pendantExp == 3 ? 2 : owner.pendantExp, owner.pendantExp * 10));
                } else {
                    pendantOfSpirit.cancel(false);
                }
            }, 3600000); //1 hour
        }
    }

    private void unequipPendantOfSpirit() {
        if (pendantOfSpirit != null) {
            pendantOfSpirit.cancel(false);
            pendantOfSpirit = null;
        }
        owner.pendantExp = 0;
    }

    /** 清空精灵吊坠计时器（Character.empty 调用） */
    void clearPendantOfSpirit() {
        if (pendantOfSpirit != null) {
            pendantOfSpirit.cancel(true);
        }
        pendantOfSpirit = null;
    }


}
