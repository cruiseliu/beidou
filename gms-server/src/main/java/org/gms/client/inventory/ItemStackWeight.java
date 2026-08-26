package org.gms.client.inventory;

public class ItemStackWeight {
    final int itemId;
    final int quantity;
    final double weight;

    public ItemStackWeight(int itemId, int quantity, double weight) {
        this.itemId = itemId;
        this.quantity = quantity;
        this.weight = weight;
    }
}
