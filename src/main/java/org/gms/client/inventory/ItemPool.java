package org.gms.client.inventory;

import java.util.ArrayList;
import java.util.List;

public class ItemPool {
    List<ItemStackWeight> items;

    public ItemPool(List<ItemStackWeight> items) {
        this.items = items;
    }

    public ItemPool() {
        items = new ArrayList<>();
    }

    public void add(int itemId, int quantity, double weight) {
        items.add(new ItemStackWeight(itemId, quantity, weight));
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
