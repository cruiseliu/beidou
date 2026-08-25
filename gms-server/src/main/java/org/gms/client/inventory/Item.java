package org.gms.client.inventory;

import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;

/**
 * 物品本体：与槽位无关的物品数据（id/宠物/署名/旗标/到期 + 现金域/装备域组件）。
 * 槽位概念（position/quantity）在 {@link ItemSlot}——本类经其构造（slot 引用仅用于
 * 装备域组件的宿主接线）；ItemSlot 保留全部旧方法作为重构期兼容门面。
 */
public class Item implements Comparable<Item> {

    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    private final int id;
    /** 宿主槽位（构造时接线；装备域 asItem/forceUpdateItem 等槽位语境经此返回） */
    private final ItemSlot slot;
    /** 点券物品会话信息（cashId/sn/giftFrom）；构造时按 isCash 定性——非现金物品恒为 null */
    private final CashItemInfo cashInfo;
    /** 装备域信息（属性数组/成长等级等）；构造时按背包类型定性——非装备物品恒为 null */
    Equip equipInfo;   // 包内可见：ItemSlot.copy 深拷贝接线
    private int petid = -1;
    String owner = "";   // 包内可见：ItemSlot.copy
    int flag;   // 包内可见：ItemSlot.copy
    long expiration = -1;   // 包内可见：ItemSlot.copy

    Item(ItemSlot slot, int id, int position, int petid) {
        this.id = id;
        this.slot = slot;
        this.petid = petid;
        this.flag = 0;
        this.cashInfo = ii.isCash(id) ? new CashItemInfo() : null;
        this.equipInfo = getInventoryType() == InventoryType.EQUIP ? new Equip(this, id) : null;
    }

    public int getItemId() {
        return id;
    }

    /** 宿主槽位 */
    public ItemSlot getSlot() {
        return slot;
    }

    public InventoryType getInventoryType() {
        return ItemConstants.getInventoryType(id);
    }

    public int getItemType() { // 1: equip, 3: pet, 2: other
        if (equipInfo != null) {
            return 1;
        }
        if (petid > -1) {
            return 3;
        }
        return 2;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public int getPetId() {
        return petid;
    }

    public int getFlag() {
        return flag;
    }

    public void setFlag(int b) {
        if (ii.isAccountRestricted(id)) {
            b |= ItemConstants.ACCOUNT_SHARING; // thanks Shinigami15 for noticing ACCOUNT_SHARING flag not being applied properly to items server-side
        }
        this.flag = b;
    }

    public long getExpiration() {
        return expiration;
    }

    public void setExpiration(long expire) {
        this.expiration = !ItemConstants.isPermanentItem(id) ? expire : ItemConstants.isPet(id) ? Long.MAX_VALUE : -1;
    }

    /** 点券物品会话信息；非现金物品为 null */
    public CashItemInfo getCashInfo() {
        return cashInfo;
    }

    /** 是否现金物品（构造时按 isCash 定性；cashId/sn/giftFrom 仅现金物品携带） */
    public boolean isCashItem() {
        return cashInfo != null;
    }

    /** 装备域信息；非装备物品为 null */
    public Equip getEquipInfo() {
        return equipInfo;
    }

    @Override
    public int compareTo(Item other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public String toString() {
        return "Item: " + id;
    }
}
