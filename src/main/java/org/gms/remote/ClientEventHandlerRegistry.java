package org.gms.remote;

import org.gms.remote.modules.cashshop.CashShopModule;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.map.client.MapModule;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.modules.quest.QuestModule;

public class ClientEventHandlerRegistry {
    PetModule.Handler pet = null;
    InventoryModule.Handler inventory = null;
    MapModule.Handler map = null;
    CashShopModule.Handler cashShop = null;
    QuestModule.Handler quest = null;

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

    public void registerMap(MapModule.Handler mapHandler) {
        map = mapHandler;
    }

    public MapModule.Handler map() {
        return map;
    }

    public void registerCashShop(CashShopModule.Handler cashShopHandler) {
        cashShop = cashShopHandler;
    }

    public CashShopModule.Handler cashShop() {
        return cashShop;
    }

    public void registerQuest(QuestModule.Handler questHandler) {
        quest = questHandler;
    }

    public QuestModule.Handler quest() {
        return quest;
    }
}
