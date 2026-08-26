package org.gms.client.inventory;

/**
 * 物品堆引用：itemId + 数量，可选携带宿主 ItemSlot（构造不初始化，由使用方按需接线——
 * 惰性解析场景下大量实例只需 id/数量，避免无谓的物品查找）。
 * 字段 package-private：同包（inventory 域）直接读写，外部经后续访问器。
 */
public class ItemStack {
    public static final int REMOVE_ALL = Integer.MAX_VALUE;

    final int itemId;
    int quantity;
    Item item;

    public ItemStack(int itemId, int quantity) {
        this.itemId = itemId;
        this.quantity = quantity;
    }

    public ItemStack(Item item, int quantity) {
        this.itemId = item.id;
        this.quantity = quantity;
        this.item = item;
    }

    public ItemStack copy() {
        ItemStack ret = new ItemStack(itemId, quantity);
        ret.item = item;
        return ret;
    }

    public ItemStack takeAtMost(int n) {
        ItemStack ret = copy();
        ret.quantity = Math.min(n, quantity);
        quantity -= ret.quantity;
        return ret;
    }
}
