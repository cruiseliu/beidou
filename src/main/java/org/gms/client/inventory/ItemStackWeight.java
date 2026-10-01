package org.gms.client.inventory;

public class ItemStackWeight {
    final ItemStack stack;
    final double weight;

    public ItemStackWeight(int itemId, int quantity, double weight) {
        this.stack = new ItemStack(itemId, quantity);
        this.weight = weight;
    }

    public ItemStackWeight(Item item, int quantity, double weight) {
        this.stack = new ItemStack(item, quantity);
        this.weight = weight;
    }
}
