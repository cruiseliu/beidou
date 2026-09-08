package org.gms.remote;

import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.pet.PetModule;

public class ClientEventHandlerRegistry {
    PetModule.Handler pet = null;
    InventoryModule.Handler inventory = null;

    public void registerPet(PetModule.Handler petHandler) {
        pet = petHandler;
    }

    public void registerInventory(InventoryModule.Handler inventoryHandler) {
        inventory = inventoryHandler;
    }

    public PetModule.Handler pet() {
        return pet;
    }

    public InventoryModule.Handler inventory() {
        return inventory;
    }
}
