package org.gms.client.inventory;

/**
 * @author kevin
 */
public class ModifyInventory {

    private final int mode;
    private ItemSlot item;
    private short oldPos;

    public ModifyInventory(final int mode, final ItemSlot item) {
        this.mode = mode;
        this.item = item.copy();
    }

    public ModifyInventory(final int mode, final ItemSlot item, final short oldPos) {
        this.mode = mode;
        this.item = item.copy();
        this.oldPos = oldPos;
    }

    public final int getMode() {
        return mode;
    }

    public final int getInventoryType() {
        return item.getInventoryType().getType();
    }

    public final short getPosition() {
        return (short) item.getPosition();
    }

    public final short getOldPosition() {
        return oldPos;
    }

    public final short getQuantity() {
        return (short) item.getQuantity();
    }

    public final ItemSlot getItem() {
        return item;
    }

    public final void clear() {
        this.item = null;
    }
}
