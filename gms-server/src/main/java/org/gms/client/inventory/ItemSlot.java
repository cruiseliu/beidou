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

import org.gms.client.inventory.manipulator.KarmaManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;

/**
 * 背包槽位：position + quantity，持物品本体 {@link Item}。
 * 其余全部方法为重构期兼容门面（委托 Item）——外部调用方零改动；
 * 新代码经 {@link #getItem()} 直达本体，门面随迁移逐步退役。
 */
public class ItemSlot implements Comparable<ItemSlot> {

    private final Item item;
    private int position;
    private int quantity;

    /** 装备物品工厂：创建槽位并携带装备域信息（成长槽等装备数据由数据源显式设置） */
    public static ItemSlot equipItem(int id, int position) {
        return new ItemSlot(id, position, 1);
    }

    public ItemSlot(int id, int position, int quantity) {
        this.item = new Item(this, id, position, -1);
        this.position = position;
        this.quantity = quantity;
    }

    public ItemSlot(int id, int position, int quantity, int petid) {
        this.item = new Item(this, id, position, petid);
        this.position = position;
        this.quantity = quantity;
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
}
