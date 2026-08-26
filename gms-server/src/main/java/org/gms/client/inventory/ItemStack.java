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

    /** 是否可充值物品（飞镖/子弹）——quantity 语义为"组数"，次数语义由宿主 Item.charge 承载 */
    public boolean isRechargeable() {
        return org.gms.constants.inventory.ItemConstants.isRechargeable(itemId);
    }

    /** 堆叠上限：可充值恒 1，其余查 wz slotMax */
    public int getStackLimit(org.gms.client.Client client) {
        return isRechargeable() ? 1 : org.gms.server.ItemInformationProvider.getInstance().getSlotMax(client, itemId);
    }

    public ItemStack takeAtMost(int n) {
        ItemStack ret = copy();
        ret.quantity = Math.min(n, quantity);
        quantity -= ret.quantity;
        return ret;
    }
}
