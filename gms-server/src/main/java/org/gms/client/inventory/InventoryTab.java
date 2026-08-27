/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.client.inventory;

import org.gms.util.Locks;
import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.server.ThreadManager;
import org.gms.util.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Matze, Ronan
 */
public class InventoryTab implements Iterable<ItemSlot> {
    protected final Map<Integer, ItemSlot> inventory;
    protected final InventoryType type;
    protected final Lock lock = new ReentrantLock(true);

    protected Character owner;
    protected int slotLimit;
    protected boolean checked = false;

    public InventoryTab(Character mc, InventoryType type, int slotLimit) {
        this.owner = mc;
        this.inventory = new LinkedHashMap<>();
        this.type = type;
        this.slotLimit = slotLimit;
    }

    public int getSlotLimit() {
        try (var ignored = Locks.acquire(lock)) {
            return slotLimit;
        }
    }

    public void setSlotLimit(int newLimit) {
        try (var ignored = Locks.acquire(lock)) {
            if (newLimit < slotLimit) {
                List<Integer> toRemove = new LinkedList<>();
                for (ItemSlot it : list()) {
                    if (it.getPosition() > newLimit) {
                        toRemove.add(it.getPosition());
                    }
                }

                for (Integer slot : toRemove) {
                    removeSlot(slot);
                }
            }

            slotLimit = newLimit;
        }
    }

    public Collection<ItemSlot> list() {
        try (var ignored = Locks.acquire(lock)) {
            return new ArrayList<>(inventory.values());
        }
    }

    public ItemSlot findById(int itemId) {
        for (ItemSlot item : list()) {
            if (item.getItemId() == itemId) {
                return item;
            }
        }
        return null;
    }

    public int countById(int itemId) {
        int qty = 0;
        for (ItemSlot item : list()) {
            if (item.getItemId() == itemId) {
                qty += item.getQuantity();
            }
        }
        return qty;
    }

    public List<ItemSlot> listById(int itemId) {
        List<ItemSlot> ret = new ArrayList<>();
        for (ItemSlot item : list()) {
            if (item.getItemId() == itemId) {
                ret.add(item);
            }
        }

        if (ret.size() > 1) {
            ret.sort((i1, i2) -> i1.getPosition() - i2.getPosition());
        }

        return ret;
    }

    // todo: [refactor] remove this overload
    public int addItem(ItemSlot item) {
        int slotId = addSlot(item);
        if (slotId == -1) {
            return -1;
        }
        item.setPosition(slotId);
        return slotId;
    }

    /** 入包放置明细（发生序）：mergedExisting=并堆（终值已写入该槽）；否则全新槽 */
    record StackPlacement(boolean mergedExisting, ItemSlot slot) {
    }

    /**
     * 事务级入包（实现贴近原 InventoryTransaction.addInternal）：先并同类可合并堆
     * （判定收敛 Item.canMergeWith），溢出逐组开新槽；放不下时已生效部分保留。
     * 宿主未接线的装备以 wz 模板工厂生成，数量非 1 拒绝；
     * EQUIPPED/CANHOLD/UNDEFINED 不支持。
     *
     * @param placements 每个被改动槽的明细（发生序），调用方各自翻译成 SlotOp / ModifyInventory
     * @return 未放入的剩余量（0 = 全部放入）
     */
    int addInternal(ItemStack stack, List<StackPlacement> placements) {
        if (type == InventoryType.EQUIPPED || type == InventoryType.CANHOLD || type == InventoryType.UNDEFINED) {
            throw new UnsupportedOperationException("不支持该背包类型: " + type);
        }
        ItemStack remaining = stack.copy();   // 游标在副本上消耗，不碰调用方入参

        if (type == InventoryType.EQUIP && remaining.quantity != 1) {
            throw new IllegalArgumentException("装备数量恒为 1: " + remaining.itemId + " x" + remaining.quantity);
        }

        int stackLimit = remaining.getStackLimit();

        if (stackLimit > 1) {
            for (ItemSlot slot : listById(remaining.itemId)) {
                if (remaining.quantity == 0) {
                    return 0;
                }
                if (!remaining.canMergeWith(slot.getItem())) {
                    continue;
                }
                if (slot.getQuantity() < stackLimit) {
                    slot.quantity += remaining.takeAtMost(stackLimit - slot.getQuantity()).quantity;
                    placements.add(new StackPlacement(true, slot));
                }
            }
        }

        while (remaining.quantity > 0) {
            ItemStack unit = remaining.takeAtMost(stackLimit);
            ItemSlot fresh = buildUnit(unit);
            if (addItem(fresh) == -1) {
                remaining.quantity += unit.quantity;   // 未放入，游标归还该组
                return remaining.quantity;
            }
            placements.add(new StackPlacement(false, fresh));
        }
        return 0;
    }

    /** 单组内容物构建：宿主已接线则原样承载；无宿主装备走 wz 模板工厂，其余默认构造 */
    private ItemSlot buildUnit(ItemStack unit) {
        if (unit.getItem() != null || type != InventoryType.EQUIP) {
            return ItemSlot.fromStack(unit, 0);
        }
        return ItemInformationProvider.getInstance().getEquipById(unit.itemId);
    }

    // find an empty slot to host the stack, or return null if no room
    // this method will never merge it with other slots
    public ItemSlot addStack(ItemStack item) {
        ItemSlot slot = ItemSlot.fromStack(item, 0);
        int index = addItem(slot);
        return index == -1 ? null : slot;
    }

    public void addItemFromDB(ItemSlot item) {
        if (item.getPosition() < 0 && !type.equals(InventoryType.EQUIPPED)) {
            return;
        }
        addSlotFromDB(item.getPosition(), item);
    }

    private static boolean isSameOwner(ItemSlot source, ItemSlot target) {
        return source.getOwner().equals(target.getOwner());
    }

    public void move(int sSlot, int dSlot, int slotMax) {
        try (var ignored = Locks.acquire(lock)) {
            ItemSlot source = inventory.get(sSlot);
            ItemSlot target = inventory.get(dSlot);
            if (source == null) {
                return;
            }
            if (target == null) {
                source.setPosition(dSlot);
                inventory.put(dSlot, source);
                inventory.remove(sSlot);
            } else if (target.getItemId() == source.getItemId() && !ItemConstants.isRechargeable(source.getItemId()) && isSameOwner(source, target)) {
                if (type.getType() == InventoryType.EQUIP.getType() || type.getType() == InventoryType.CASH.getType()) {
                    swap(target, source);
                } else if (source.getQuantity() + target.getQuantity() > slotMax) {
                    int rest = (source.getQuantity() + target.getQuantity()) - slotMax;
                    source.setQuantity(rest);
                    target.setQuantity(slotMax);
                } else {
                    target.setQuantity(source.getQuantity() + target.getQuantity());
                    inventory.remove(sSlot);
                }
            } else {
                swap(target, source);
            }
        }
    }

    private void swap(ItemSlot source, ItemSlot target) {
        inventory.remove(source.getPosition());
        inventory.remove(target.getPosition());
        int swapPos = source.getPosition();
        source.setPosition(target.getPosition());
        target.setPosition(swapPos);
        inventory.put(source.getPosition(), source);
        inventory.put(target.getPosition(), target);
    }

    public ItemSlot getItem(int slot) {
        try (var ignored = Locks.acquire(lock)) {
            return inventory.get(slot);
        }
    }

    public void removeItem(int slot) {
        removeItem(slot, 1, false);
    }

    public void removeItem(int slot, int quantity, boolean allowZero) {
        ItemSlot item = getItem(slot);
        if (item == null) {  // TODO is it ok not to throw an exception here?
            return;
        }
        item.setQuantity(item.getQuantity() - quantity);
        if (item.getQuantity() < 0) {
            item.setQuantity(0);
        }
        if (item.getQuantity() == 0 && !allowZero) {
            removeSlot(slot);
        }
    }

    protected int addSlot(ItemSlot item) {
        if (item == null) {
            return -1;
        }

        int slotId;
        try (var ignored = Locks.acquire(lock)) {
            slotId = getNextFreeSlot();
            if (slotId < 0) {
                return -1;
            }

            inventory.put(slotId, item);
        }

        if (ItemConstants.isRateCoupon(item.getItemId())) {
            // deadlocks with coupons rates found thanks to GabrielSin & Masterrulax
            ThreadManager.getInstance().newTask(() -> owner.updateCouponRates());
        }

        return slotId;
    }

    protected void addSlotFromDB(int slot, ItemSlot item) {
        try (var ignored = Locks.acquire(lock)) {
            inventory.put(slot, item);
        }

        if (ItemConstants.isRateCoupon(item.getItemId())) {
            ThreadManager.getInstance().newTask(() -> owner.updateCouponRates());
        }
    }

    public void removeSlot(int slot) {
        ItemSlot item;
        try (var ignored = Locks.acquire(lock)) {
            item = inventory.remove(slot);
        }

        if (item != null && ItemConstants.isRateCoupon(item.getItemId())) {
            ThreadManager.getInstance().newTask(() -> owner.updateCouponRates());
        }
    }

    public boolean isFull() {
        try (var ignored = Locks.acquire(lock)) {
            return inventory.size() >= slotLimit;
        }
    }

    public boolean isFull(int margin) {
        try (var ignored = Locks.acquire(lock)) {
            //System.out.print("(" + inventory.size() + " " + margin + " <> " + slotLimit + ")");
            return inventory.size() + margin >= slotLimit;
        }
    }

    public boolean isFullAfterSomeItems(int margin, int used) {
        try (var ignored = Locks.acquire(lock)) {
            //System.out.print("(" + inventory.size() + " " + margin + " <> " + slotLimit + " -" + used + ")");
            return inventory.size() + margin >= slotLimit - used;
        }
    }

    public int getNextFreeSlot() {
        if (isFull()) {
            return -1;
        }

        try (var ignored = Locks.acquire(lock)) {
            for (int i = 1; i <= slotLimit; i++) {
                if (!inventory.containsKey(i)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public int getNumFreeSlot() {
        if (isFull()) {
            return 0;
        }

        try (var ignored = Locks.acquire(lock)) {
            int free = 0;
            for (int i = 1; i <= slotLimit; i++) {
                if (!inventory.containsKey(i)) {
                    free++;
                }
            }
            return free;
        }
    }

    private static boolean checkItemRestricted(List<Pair<ItemSlot, InventoryType>> items) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        // thanks Shavit for noticing set creation that would be only effective in rare situations
        for (Pair<ItemSlot, InventoryType> p : items) {
            int itemid = p.getLeft().getItemId();
            if (ii.isPickupRestricted(itemid) && p.getLeft().getQuantity() > 1) {
                return false;
            }
        }

        return true;
    }

    public static boolean checkSpot(Character chr, ItemSlot item) {    // thanks Vcoc for noticing pshops not checking item stacks when taking item back
        return checkSpot(chr, Collections.singletonList(item));
    }

    public static boolean checkSpot(Character chr, List<ItemSlot> items) {
        List<Pair<ItemSlot, InventoryType>> listItems = new LinkedList<>();
        for (ItemSlot item : items) {
            listItems.add(new Pair<>(item, item.getInventoryType()));
        }

        return checkSpotsAndOwnership(chr, listItems);
    }

    public static boolean checkSpots(Character chr, List<Pair<ItemSlot, InventoryType>> items) {
        return checkSpots(chr, items, false);
    }

    public static boolean checkSpots(Character chr, List<Pair<ItemSlot, InventoryType>> items, boolean useProofInv) {
        int invTypesSize = InventoryType.values().length;
        List<Integer> zeroedList = new ArrayList<>(invTypesSize);
        for (int i = 0; i < invTypesSize; i++) {
            zeroedList.add(0);
        }

        return checkSpots(chr, items, zeroedList, useProofInv);
    }

    public static boolean checkSpots(Character chr, List<Pair<ItemSlot, InventoryType>> items, List<Integer> typesSlotsUsed, boolean useProofInv) {
        // assumption: no "UNDEFINED" or "EQUIPPED" items shall be tested here, all counts are >= 0.

        if (!checkItemRestricted(items)) {
            return false;
        }

        Map<Integer, List<Integer>> rcvItems = new LinkedHashMap<>();
        Map<Integer, Integer> rcvTypes = new LinkedHashMap<>();

        for (Pair<ItemSlot, InventoryType> item : items) {
            Integer itemId = item.left.getItemId();
            List<Integer> qty = rcvItems.get(itemId);

            if (qty == null) {
                List<Integer> itemQtyList = new LinkedList<>();
                itemQtyList.add((int) item.left.getQuantity());

                rcvItems.put(itemId, itemQtyList);
                rcvTypes.put(itemId, (int) item.right.getType());
            } else {
                if (!ItemConstants.isEquipment(itemId) && !ItemConstants.isRechargeable(itemId)) {
                    qty.set(0, qty.get(0) + item.left.getQuantity());
                } else {
                    qty.add((int) item.left.getQuantity());
                }
            }
        }

        Client c = chr.getClient();
        for (Entry<Integer, List<Integer>> it : rcvItems.entrySet()) {
            int itemType = rcvTypes.get(it.getKey()) - 1;

            for (Integer itValue : it.getValue()) {
                int usedSlots = typesSlotsUsed.get(itemType);

                int result = InventoryManipulator.checkSpaceProgressively(c, it.getKey(), itValue, "", usedSlots, useProofInv);
                boolean hasSpace = ((result % 2) != 0);

                if (!hasSpace) {
                    return false;
                }
                typesSlotsUsed.set(itemType, (result >> 1));
            }
        }

        return true;
    }

    private static long fnvHash32(final String k) {
        final int FNV_32_INIT = 0x811c9dc5;
        final int FNV_32_PRIME = 0x01000193;

        int rv = FNV_32_INIT;
        final int len = k.length();
        for (int i = 0; i < len; i++) {
            rv ^= k.charAt(i);
            rv *= FNV_32_PRIME;
        }

        return rv >= 0 ? rv : (2L * Integer.MAX_VALUE) + rv;
    }

    private static Long hashKey(Integer itemId, String owner) {
        return (itemId.longValue() << 32L) + fnvHash32(owner);
    }

    public static boolean checkSpotsAndOwnership(Character chr, List<Pair<ItemSlot, InventoryType>> items) {
        return checkSpotsAndOwnership(chr, items, false);
    }

    public static boolean checkSpotsAndOwnership(Character chr, List<Pair<ItemSlot, InventoryType>> items, boolean useProofInv) {
        List<Integer> zeroedList = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            zeroedList.add(0);
        }

        return checkSpotsAndOwnership(chr, items, zeroedList, useProofInv);
    }

    public static boolean checkSpotsAndOwnership(Character chr, List<Pair<ItemSlot, InventoryType>> items, List<Integer> typesSlotsUsed, boolean useProofInv) {
        //assumption: no "UNDEFINED" or "EQUIPPED" items shall be tested here, all counts are >= 0 and item list to be checked is a legal one.

        if (!checkItemRestricted(items)) {
            return false;
        }

        Map<Long, List<Integer>> rcvItems = new LinkedHashMap<>();
        Map<Long, Integer> rcvTypes = new LinkedHashMap<>();
        Map<Long, String> rcvOwners = new LinkedHashMap<>();

        for (Pair<ItemSlot, InventoryType> item : items) {
            Long itemHash = hashKey(item.left.getItemId(), item.left.getOwner());
            List<Integer> qty = rcvItems.get(itemHash);

            if (qty == null) {
                List<Integer> itemQtyList = new LinkedList<>();
                itemQtyList.add((int) item.left.getQuantity());

                rcvItems.put(itemHash, itemQtyList);
                rcvTypes.put(itemHash, (int) item.right.getType());
                rcvOwners.put(itemHash, item.left.getOwner());
            } else {
                // thanks BHB88 for pointing out an issue with rechargeable items being stacked on inventory check
                if (!ItemConstants.isEquipment(item.left.getItemId()) && !ItemConstants.isRechargeable(item.left.getItemId())) {
                    qty.set(0, qty.get(0) + item.left.getQuantity());
                } else {
                    qty.add((int) item.left.getQuantity());
                }
            }
        }

        Client c = chr.getClient();
        for (Entry<Long, List<Integer>> it : rcvItems.entrySet()) {
            int itemType = rcvTypes.get(it.getKey()) - 1;
            int itemId = (int) (it.getKey() >> 32L);

            for (Integer itValue : it.getValue()) {
                int usedSlots = typesSlotsUsed.get(itemType);

                //System.out.print("inserting " + itemId.intValue() + " with type " + itemType + " qty " + it.getValue() + " owner '" + rcvOwners.get(it.getKey()) + "' current usedSlots:");
                //for(Integer i : typesSlotsUsed) System.out.print(" " + i);
                int result = InventoryManipulator.checkSpaceProgressively(c, itemId, itValue, rcvOwners.get(it.getKey()), usedSlots, useProofInv);
                boolean hasSpace = ((result % 2) != 0);
                //System.out.print(" -> hasSpace: " + hasSpace + " RESULT : " + result + "\n");

                if (!hasSpace) {
                    return false;
                }
                typesSlotsUsed.set(itemType, (result >> 1));
            }
        }

        return true;
    }

    public InventoryType getType() {
        return type;
    }

    @Override
    public Iterator<ItemSlot> iterator() {
        return Collections.unmodifiableCollection(list()).iterator();
    }

    public ItemSlot findByCashId(int cashId) {
        boolean isRing = false;
        Equip equip = null;
        for (ItemSlot item : list()) {
            if (item.getInventoryType().equals(InventoryType.EQUIP)) {
                equip = item.getEquipInfo();
                isRing = equip.getRingId() > -1;
            }
            if ((item.getPetId() > -1 ? item.getPetId() : isRing ? equip.getRingId() : item.getCashInfo() != null ? item.getCashInfo().getCashId() : 0) == cashId) {
                return item;
            }
        }

        return null;
    }

    public boolean checked() {
        try (var ignored = Locks.acquire(lock)) {
            return checked;
        }
    }

    public void checked(boolean yes) {
        try (var ignored = Locks.acquire(lock)) {
            checked = yes;
        }
    }

    public void lockInventory() {
        lock.lock();
    }

    public void unlockInventory() {
        lock.unlock();
    }

    public void dispose() {
        owner = null;
    }
}
