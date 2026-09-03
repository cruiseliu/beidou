package org.gms.client.inventory;

/**
 * 物品堆引用：itemId + 数量，可选携带宿主 Item（构造不初始化，由使用方按需接线——
 * 惰性解析场景下大量实例只需 id/数量，避免无谓的物品查找；可充值组以宿主 Item
 * 携带次数 charge，无宿主时落位取 wz 满组）。
 * 字段 package-private：同包（inventory 域）直接读写，外部经后续访问器。
 */
public class ItemStack {
    public static final int REMOVE_ALL = Integer.MAX_VALUE;

    final int itemId;
    int quantity;
    Item item = null;  // lazy construction on getItem()

    /** Initiate ItemStack from external data, where quantity may indicates charge */
    public static ItemStack fromExternal(int itemId, int quantity) {
        if (Item.isRechargeable(itemId)) {
            Item item = new Item(itemId);
            item.charge = quantity;
            return new ItemStack(item, 1);
        } else {
            return new ItemStack(itemId, quantity);
        }
    }

    public ItemStack(int itemId, int quantity) {
        if (org.gms.constants.inventory.ItemConstants.isPet(itemId)) {
            // 宠物物品必须经带 petId 的构造（宿主 Item）——无主 itemId 构造会产出宠物系统不识别的空壳
            throw new IllegalArgumentException("禁止用 itemId 构造宠物 ItemStack: " + itemId + "，请携带宿主 Item（petId）");
        }
        this.itemId = itemId;
        this.quantity = quantity;
    }

    public ItemStack(Item item, int quantity) {
        this.itemId = item.id;
        this.quantity = quantity;
        this.item = item;
    }

    ItemStack deepCopy() {
        return item == null ? new ItemStack(itemId, quantity) : new ItemStack(item.copy(), quantity);
    }

    ItemStack shallowCopy() {
        return item == null ? new ItemStack(itemId, quantity) : new ItemStack(item, quantity);
    }

    /** 堆叠上限 */
    int getStackLimit() {
        return Item.getStackLimit(itemId);
    }

    /**
     * 并堆判定门面：宿主未接线时视作"该 itemId 的默认构造实例"
     * （wz 类型旗标落位后的无主物品——与背包既有堆的可达状态一致）。
     */
    boolean canMergeWith(Item existing) {
        return getItem().canMergeWith(existing);
    }

    ItemStack takeAtMost(int n) {
        ItemStack ret = deepCopy();
        ret.quantity = Math.min(n, quantity);
        quantity -= ret.quantity;
        return ret;
    }

    Item getItem() {
        if (item == null) {
            item = new Item(itemId);
        }
        return item;
    }
}
