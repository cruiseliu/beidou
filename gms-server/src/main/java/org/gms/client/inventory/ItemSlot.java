/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

This program is free software under the GNU Affero General Public License
version 3 as published by the Free Software Foundation, see LICENSE for details.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.
 */
package org.gms.client.inventory;

import org.gms.client.Client;
import org.gms.client.inventory.manipulator.KarmaManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.constants.inventory.ItemConstants;
import org.gms.model.json.ItemData;
import org.gms.server.ItemInformationProvider;

/**
 * 背包槽位：position + quantity，持物品本体 {@link Item}。
 * 其余全部方法为重构期兼容门面（委托 Item）——外部调用方零改动；
 * 新代码经 {@link #getItem()} 直达本体，门面随迁移逐步退役。
 */
public class ItemSlot implements Comparable<ItemSlot> {
    private static final Logger log = LoggerFactory.getLogger(ItemSlot.class);
    final Item item;
    int position;
    int quantity;

    /** 装备物品工厂：创建槽位并携带装备域信息（成长槽等装备数据由数据源显式设置） */
    public static ItemSlot equipItem(int id, int position) {
        return new ItemSlot(id, position, 1);
    }

    public static ItemSlot fromStack(ItemStack stack, int position) {
        ItemSlot slot;
        if (stack.item == null) {
            slot = new ItemSlot(stack.itemId, position, stack.quantity);
        } else {
            slot = new ItemSlot(stack.item, position, stack.quantity);
        }
        // 可充值：次数语义在宿主 Item 的 charge——宿主携带 > 未指定落位取 wz 满组
        if (slot.isRechargeable()) {
            if (stack.item != null && stack.item.charge > 0) {
                slot.getItem().setCharge(stack.item.charge);
            } else {
                slot.getItem().setCharge(ItemInformationProvider.getInstance().getSlotMax(null, stack.itemId));
            }
        }
        return slot;
    }

    private ItemSlot(Item item, int position, int quantity) {
        this.item = item;
        this.position = position;
        this.quantity = normalizeQuantity(item, quantity);
    }

    public ItemSlot(int id, int position, int quantity) {
        this.item = new Item(id, position, -1);
        this.position = position;
        this.quantity = normalizeQuantity(this.item, quantity);
    }

    public ItemSlot(int id, int position, int quantity, int petid) {
        this.item = new Item(id, position, petid);
        this.position = position;
        this.quantity = normalizeQuantity(this.item, quantity);
    }

    /**
     * 可充值物品的表示归一：quantity 恒 1（一组一格），原数量（次数）转入 {@link Item#charge}。
     * 传入 quantity &gt; 1 视为旧格式（次数直存 quantity），转换并 log warning。
     */
    private static int normalizeQuantity(Item item, int quantity) {
        if (!item.isRechargeable()) {
            return quantity;
        }
        if (quantity > 1) {
            log.warn("可充值物品 {} 以旧格式构造（quantity={}），已转换为 charge；调用方应改用新表示",
                    item.getItemId(), quantity);
        }
        item.charge = quantity;
        return 1;
    }

    public ItemStack takeAtMost(int n) {
        ItemStack ret = new ItemStack(item, Math.min(n, quantity));
        quantity -= ret.quantity;
        return ret;
    }

    /** 物品本体出口（新代码用；门面方法逐步迁移后以此为准） */
    public Item getItem() {
        return item;
    }

    public ItemSlot copy() {
        ItemSlot ret = new ItemSlot(item.getItemId(), position, quantity, item.getPetId());
        ret.item.flag = item.flag;
        ret.item.owner = item.owner;
        ret.item.expiration = item.expiration;
        ret.item.charge = item.charge;
        if (item.equipInfo != null) {
            ret.item.equipInfo = item.equipInfo.copy(ret.item);
        }
        return ret;
    }

    // ── 槽位自身 ──

    public void setPosition(int position) {
        this.position = position;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getPosition() {
        return position;
    }

    public int getQuantity() {
        return quantity;
    }

    /** 是否可充值物品（委托本体） */
    public boolean isRechargeable() {
        return item.isRechargeable();
    }

    /** 堆叠上限：可充值恒 1（一组一格），其余查 wz slotMax */
    public int getStackLimit(Client client) {
        return isRechargeable() ? 1 : ItemInformationProvider.getInstance().getSlotMax(client, item.getItemId());
    }

    /** 可使用量：可充值返回 charge（次数），其余返回 quantity */
    public int getAmmoCharge() {
        return isRechargeable() ? item.charge : quantity;
    }

    // ── 物品本体门面（委托 Item）──

    public int getItemId() {
        return item.getItemId();
    }

    public InventoryType getInventoryType() {
        return item.getInventoryType();
    }

    public int getItemType() {
        return item.getItemType();
    }

    public String getOwner() {
        return item.getOwner();
    }

    public void setOwner(String owner) {
        item.setOwner(owner);
    }

    public int getPetId() {
        return item.getPetId();
    }

    public int getFlag() {
        return item.getFlag();
    }

    public void setFlag(int b) {
        item.setFlag(b);
    }

    public long getExpiration() {
        return item.getExpiration();
    }

    public void setExpiration(long expire) {
        item.setExpiration(expire);
    }

    public CashItemInfo getCashInfo() {
        return item.getCashInfo();
    }

    public boolean isCashItem() {
        return item.isCashItem();
    }

    public Equip getEquipInfo() {
        return item.getEquipInfo();
    }

    /** 依赖 KarmaManipulator（其 API 收 ItemSlot），暂留门面层；随其签名迁移进 Item */
    public boolean isUntradeable() {
        return ((item.getFlag() & ItemConstants.UNTRADEABLE) == ItemConstants.UNTRADEABLE) || (ItemInformationProvider.getInstance().isDropRestricted(item.getItemId()) && !KarmaManipulator.hasKarmaFlag(this));
    }

    @Override
    public int compareTo(ItemSlot other) {
        return item.compareTo(other.item);
    }

    @Override
    public String toString() {
        return "Item: " + item.getItemId() + " quantity: " + quantity;
    }

    public ItemData toData() {
        ItemData d = new ItemData();
        d.itemId = item.id;
        d.position = position;
        d.quantity = quantity;
        d.flag = item.flag == 0 ? null : item.flag;
        d.owner = item.owner.isEmpty() ? null : item.owner;
        d.expiration = item.expiration == -1 ? null : item.expiration;
        d.petId = item.petId == -1 ? null : item.petId;
        if (item.equipInfo != null) {
            d.equip = item.equipInfo.toData();
        }
        return d;
    }

    public void applyData(ItemData d) {
        position = d.position;
        quantity = d.quantity;
        if (d.flag != null) {
            item.flag = d.flag;
        }
        if (d.owner != null) {
            item.owner = d.owner;
        }
        if (d.expiration != null) {
            item.expiration = d.expiration;
        }
        if (d.petId != null) {
            item.petId = d.petId;
        }
        if (d.equip != null) {
            item.equipInfo.applyData(d.equip);
        }
    }
}
