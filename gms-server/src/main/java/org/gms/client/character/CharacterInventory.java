package org.gms.client.character;

import java.util.Map;
import java.util.LinkedHashMap;
import org.gms.model.json.ItemData;
import org.gms.client.Client;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemSlot;
import org.gms.server.CashShop;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MapObject;
import org.gms.scripting.item.ItemScript;
import org.gms.scripting.item.ItemScriptManager;

import java.util.LinkedList;

import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.config.GameConfig;
import org.gms.constants.id.ItemId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.constants.id.MapId;
import org.gms.net.packet.Packet;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.inventory.client.UseItemEvent;
import org.gms.remote.modules.inventory.server.SlotChange;
import org.gms.server.ItemInformationProvider.ScriptedItem;
import org.gms.server.TimerManager;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.gms.client.inventory.ItemFlag;
import org.gms.client.inventory.ItemStack;

/**
 * 背包模块组件：各类型背包（inventories）+ 槽位（slots）+ 物品查询/持有判定
 * + 物品过期（itemExpireTask）+ 沙盒物品 + 出售/扩容。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getInventory/canHold/... 对外转发）。
 * 装备域在子模块 {@link CharacterEquips}（getEquips()，关系对齐 CharacterBuffs↔ActiveBuffs）。
 *
 * 边界：只承载背包语义——物品存储、槽位、持有/容量判定、物品过期、出售。
 * 拾取流程（pickupItem）等跨域编排留在 Character；
 * 持久化 SQL（inventoryitems/inventoryequipment、slots 列）留在 Character.saveCharToDB；
 * 依赖经 owner 门面调用（sendPacket/getMap/gainMeso/dropMessage/...）。
 */
class CharacterInventory implements InventoryModule.Handler {
    private static final Logger log = LoggerFactory.getLogger(CharacterInventory.class);

    private final Character owner;

    /** 背包集合（持有全部 InventoryTab；重构期直接暴露内部数组） */
    Inventory inventory;

    private int slots = 0;
    /** 装备域子模块（穿戴编排/装备经验/精灵吊坠/已装备查询） */
    private final CharacterEquips equips;
    /** 沙盒物品标志 */
    private boolean hasSandboxItem = false;
    /** 物品过期扫描定时器 */
    private ScheduledFuture<?> itemExpireTask = null;
    /** 混沌卷轴（制造时使用） */
    private boolean useCS;

    CharacterInventory(Character owner) {
        this.owner = owner;
        useCS = false;
        inventory = new Inventory(owner);
        this.equips = new CharacterEquips(owner, inventory.tabs[InventoryType.EQUIPPED.ordinal()]);
        // 不在构造期自注册：构造上下文无 actor 可达（autosave/charlist 装载，doc/12）
    }

    /** 收包 Handler 接插（角色入场绑定时由 Character 聚合调用，on strand，doc/12） */
    void bindClientHandlers(ClientEventHandlerRegistry registry) {
        registry.registerInventory(this);
    }

    /** 装备域子模块（对齐 CharacterBuffs.getActive() 的暴露方式） */
    CharacterEquips getEquips() {
        return equips;
    }

    public void useItem(int slotIndex, int itemId) {
        InventoryTab tab = inventory.getTab(Item.getInventoryTab(itemId));
        ItemSlot slot = tab.getItem(slotIndex);

        if (slot == null || slot.getItemId() != itemId) {
            log.error("useItem bad item id {} (slotIndex:{} slot:{})", itemId, slotIndex, slot);
            return;
        }

        ItemScript script = ItemScript.forItem(itemId);
        if (script == null || !script.hasHook(owner, ItemScript.HOOK_USE)) {
            log.error("useItem {} missing onUse script", itemId);
            return;
        }

        // 合并域包住 hook + 消耗：一次使用的全部语义更新一次 flush
        try (var batch = owner.remote().batch()) {
            boolean consumed = script.invokeUse(owner, slot.getItem());
            if (consumed) {
                tab.removeItem(slotIndex, 1, false);
            }
        }
    }

    // ── 持久化数据转换（inventory 域；信封组装在 Character.toData） ──

    Map<String, List<ItemData>> toData() {
        Map<String, List<ItemData>> data = new LinkedHashMap<>();
        for (InventoryType type : InventoryType.values()) {
            if (type == InventoryType.CANHOLD) {   // 证明背包瞬态，不序列化
                continue;
            }
            List<ItemData> items = new ArrayList<>();
            for (ItemSlot item : inventory.tabs[type.ordinal()].list()) {
                items.add(item.toData());
            }
            data.put(type.name(), items);
        }
        return data;
    }

    void applyData(Map<String, List<ItemData>> data) {
        for (InventoryType type : InventoryType.values()) {
            List<ItemData> items = data.get(type.name());
            if (items == null) {
                continue;
            }
            InventoryTab inv = inventory.tabs[type.ordinal()];
            for (ItemData d : items) {
                inv.addItemFromDB(ItemSlot.fromData(d));
            }
        }
    }

    // ── 查询 ──

    InventoryTab getInventory(InventoryType type) {
        return inventory.tabs[type.ordinal()];
    }

    /** 全部背包（saveCharToDB 遍历用；包内可见） */
    InventoryTab[] getInventories() {
        return inventory.tabs;
    }

    /** 背包集合 */
    Inventory getInventory() {
        return inventory;
    }

    /** 释放全部背包（Character.empty 调用，防内存泄漏） */
    void disposeAll() {
        inventory.disposeAll();
    }

    int countItem(int itemid) {
        return inventory.tabs[ItemConstants.getInventoryType(itemid).ordinal()].countById(itemid);
    }

    boolean canHold(int itemid) {
        return canHold(itemid, 1);
    }

    boolean canHold(int itemid, int quantity) {
        // 可放置探针直连 InventoryTab.checkSpots（原经 APII.canHoldAll 的绕道收拢，doc/13 §16）
        List<Pair<ItemSlot, InventoryType>> probes = List.of(
                new Pair<>(new ItemSlot(itemid, (short) 0, (short) quantity), ItemConstants.getInventoryType(itemid)));
        return InventoryTab.checkSpots(owner, probes);
    }

    /**
     * 发放/扣除道具（正数发放、负数扣除；showMessage 恒开）——自
     * AbstractPlayerInteraction.gainItem 终端实现按本组件用到的重载归位
     * （randomStats/expires/pet-from 通路未被脚本使用，留在 legacy，doc/13 §16）。
     */
    public ItemSlot gainItem(int itemId, int quantity) {
        Client client = owner.getClient();
        ItemSlot item = null;
        if (quantity >= 0) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            if (ItemConstants.getInventoryType(itemId).equals(InventoryType.EQUIP)) {
                item = ii.getEquipById(itemId);
                if (item != null) {
                    Equip it = item.getEquipInfo();
                    if (ItemConstants.isAccessory(itemId) && it.getEnhancementSlots() <= 0) {
                        it.setEnhancementSlots(3);
                    }
                    if (GameConfig.getServerBoolean("use_enhanced_crafting") && owner.isUseCS()) {
                        Equip eqp = item.getEquipInfo();
                        if (!(owner.isGM() && GameConfig.getServerBoolean("use_perfect_gm_scroll"))) {
                            eqp.setEnhancementSlots((byte) (eqp.getEnhancementSlots() + 1));
                        }
                        item = ii.scrollEquipWithId(item, ItemId.CHAOS_SCROll_60, true, ItemId.CHAOS_SCROll_60, owner.isGM());
                    }
                }
            } else {
                item = new ItemSlot(itemId, (short) 0, quantity, -1);
            }
            if (!InventoryManipulator.checkSpace(client, itemId, quantity, "")) {
                owner.dropMessage(1, "您的背包已满，请从" + ItemConstants.getInventoryType(itemId).name() + "栏移除一件物品。");
                return null;
            }
            InventoryManipulator.addFromDrop(client, item, false, -1);
        } else {
            InventoryManipulator.removeById(client, ItemConstants.getInventoryType(itemId), itemId, (short) -quantity, true, false);
        }
        client.sendPacket(PacketCreator.getShowItemGain(itemId, (short) quantity, true));
        return item;
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
        return (inventory.tabs[ItemConstants.getInventoryType(itemid).ordinal()].findById(itemid) != null)
                || (checkEquipped && inventory.tabs[InventoryType.EQUIPPED.ordinal()].findById(itemid) != null);
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
        int count = inventory.tabs[ItemConstants.getInventoryType(itemid).ordinal()].countById(itemid);
        if (checkEquipped) {
            count += inventory.tabs[InventoryType.EQUIPPED.ordinal()].countById(itemid);
        }
        return count;
    }

        boolean haveItem(int itemid) {
        return getItemQuantity(itemid, ItemConstants.isEquipment(itemid)) > 0;
    }

        boolean hasEmptySlot(int itemId) {
        return getInventory(ItemConstants.getInventoryType(itemId)).getNextFreeSlot() > -1;
    }

    boolean hasEmptySlot(byte invType) {
        return getInventory(InventoryType.getByType(invType)).getNextFreeSlot() > -1;
    }

    int getSlots(int type) {
        return type == InventoryType.CASH.getType() ? 96 : inventory.tabs[type].getSlotLimit();
    }

    boolean canGainSlots(int type, int slots) {
        slots += inventory.tabs[type].getSlotLimit();
        return slots <= 96;
    }

    int getSlot() {
        return slots;
    }

    void setSlot(int slotid) {
        slots = slotid;
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
        inventory.tabs[type].lockInventory();
        try {
            if (canGainSlots(type, slots)) {
                int newLimit = inventory.tabs[type].getSlotLimit() + slots;
                inventory.tabs[type].setSlotLimit(newLimit);
                return newLimit;
            } else {
                return -1;
            }
        } finally {
            inventory.tabs[type].unlockInventory();
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
                long expiration, currenttime = System.currentTimeMillis();

                List<ItemSlot> toberemove = new ArrayList<>();
                for (InventoryTab inv : inventory.tabs) {
                    for (ItemSlot item : inv.list()) {
                        expiration = item.getExpiration();

                        if (expiration != -1 && (expiration < currenttime) && item.hasFlag(ItemFlag.LOCK)) {
                            item.removeFlag(ItemFlag.LOCK); //Probably need a check, else people can make expiring items into permanent items...
                            item.setExpiration(-1);
                            forceUpdateItem(item);   //TEST :3
                        } else if (expiration != -1 && expiration < currenttime) {
                            // 宠物道具到期由 pet 模块管理（pets.expires_at + 专属计时器），inventory 不感知
                            owner.sendPacket(PacketCreator.itemExpired(item.getItemId()));
                            toberemove.add(item);
                        }
                    }

                    if (!toberemove.isEmpty()) {
                        for (ItemSlot item : toberemove) {
                            InventoryManipulator.removeFromSlot(owner.client, inv.getType(), (short) item.getPosition(), (short) item.getQuantity(), true);
                        }

                        ItemInformationProvider ii = ItemInformationProvider.getInstance();
                        for (ItemSlot item : toberemove) {
                            List<Integer> toadd = new ArrayList<>();
                            Pair<Integer, String> replace = ii.getReplaceOnExpire(item.getItemId());
                            if (replace.left > 0) {
                                toadd.add(replace.left);
                                if (!replace.right.isEmpty()) {
                                    owner.dropMessage(replace.right);
                                }
                            }
                            for (Integer itemid : toadd) {
                                inventory.add(new ItemStack(itemid, 1));
                            }
                        }

                        toberemove.clear();
                    }
                }
            }, 60000);
        }
    }

    // ── 物品操作 ──

    void forceUpdateItem(ItemSlot item) {
        short pos = (short) item.getPosition();
        owner.getRemote().inventory().updateInventory(List.of(
                new SlotChange.Removed(item.getItem(), pos),
                new SlotChange.Added(item.getItem(), pos, item.getQuantity())));
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
            InventoryTab inv = this.getInventory(invType);

            inv.lockInventory();
            try {
                for (ItemSlot item : new ArrayList<>(inv.list())) {
                    if (InventoryManipulator.isSandboxItem(item)) {
                        InventoryManipulator.removeFromSlot(owner.client, invType, (short) item.getPosition(), (short) item.getQuantity(), false);
                        owner.dropMessage(5, "[" + ii.getName(item.getItemId()) + "] " + I18nUtil.getMessage("Character.removeSandboxItems.message1"));
                    }
                }
            } finally {
                inv.unlockInventory();
            }
        }

        hasSandboxItem = false;
    }

    // ── 出售 ──

    int sellAllItemsFromPosition(ItemInformationProvider ii, InventoryType type, int pos) {
        int mesoGain = 0;

        InventoryTab inv = getInventory(type);
        inv.lockInventory();
        try {
            for (int i = pos; i <= inv.getSlotLimit(); i++) {
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

    private int standaloneSell(Client c, ItemInformationProvider ii, InventoryType type, int slot, int quantity) {
        if (quantity == 0) {
            quantity = 1;
        }

        InventoryTab inv = getInventory(type);
        inv.lockInventory();
        try {
            ItemSlot item = inv.getItem(slot);
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
            int iQuant = item.getQuantity();

            if (quantity <= iQuant && iQuant > 0) {
                InventoryManipulator.removeFromSlot(c, type, (byte) slot, (short) quantity, false);
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

    /**
     * 拾取收尾的 shim 交接（player strand 不直操作 MapleMap）：decision 时点在
     * itemLock 下同步标记 pickedUp（防同 strand 后续拾取双捡），map 注册表移除与
     * REMOVE_ITEM_FROM_MAP 广播 post map actor（任务体内重取 itemLock，满足
     * pickItemDrop 的持锁契约）。
     */
    private void completePickup(MapItem mapitem, Packet pickupPacket) {
        mapitem.setPickedUp(true);
        final MapleMap map = owner.getMap();
        map.post("pickup-drop", () -> {
            mapitem.lockItem();
            try {
                map.pickItemDrop(pickupPacket, mapitem);
            } finally {
                mapitem.unlockItem();
            }
        });
    }

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

                ItemSlot mItem = mapitem.getItem();
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

                                completePickup(mapitem, pickupPacket);
                            } else if (ItemId.isNxCard(mapitem.getItemId())) {
                                // Add NX to account, show effect and make item disappear   //添加点券到账户，是否展示捡到点券，并移除物品
                                int nxGain = (mapitem.getItemId() == ItemId.NX_CARD_100 ? 100 : 250) * mItem.getQuantity(); //使点券支持按数量相乘
                                owner.getCashShop().gainCash(CashShop.NX_CREDIT, nxGain);

                                if (GameConfig.getServerBoolean("use_announce_nx_coupon_loot")) {       //捡到点券是否展示
                                    owner.showHint(I18nUtil.getMessage("Character.pickupItem.message1", nxGain, owner.getCashShop().getCash(CashShop.NX_CREDIT)), 300);
                                    //owner.showHint("捡到 #e#b" + nxGain + " NX#k#n (" + owner.getCashShop().getCash(CashShop.NX_CREDIT) + " NX)", 300);
                                }

                                completePickup(mapitem, pickupPacket);
                            } else if (InventoryManipulator.addFromDrop(owner.client, mItem, true)) {
                                completePickup(mapitem, pickupPacket);
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

                    completePickup(mapitem, pickupPacket);
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
}
