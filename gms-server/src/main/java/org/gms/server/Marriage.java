/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.server;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.ItemFactory;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.scripting.event.EventManager;
import org.gms.util.DatabaseConnection;
import org.gms.util.Pair;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Ronan
 */
public class Marriage extends EventInstanceManager {
    public Marriage(EventManager em, String name) {
        super(em, name);
    }

    public boolean giftItemToSpouse(int cid) {
        return this.getIntProperty("wishlistSelection") == 0;
    }

    public List<String> getWishlistItems(boolean groom) {
        String strItems = this.getProperty(groom ? "groomWishlist" : "brideWishlist");
        if (strItems != null) {
            return Arrays.asList(strItems.split("\r\n"));
        }

        return new LinkedList<>();
    }

    public void initializeGiftItems() {
        List<ItemSlot> groomGifts = new ArrayList<>();
        this.setObjectProperty("groomGiftlist", groomGifts);

        List<ItemSlot> brideGifts = new ArrayList<>();
        this.setObjectProperty("brideGiftlist", brideGifts);
    }

    public List<ItemSlot> getGiftItems(Client c, boolean groom) {
        List<ItemSlot> gifts = getGiftItemsList(groom);
        synchronized (gifts) {
            return new LinkedList<>(gifts);
        }
    }

    private List<ItemSlot> getGiftItemsList(boolean groom) {
        return (List<ItemSlot>) this.getObjectProperty(groom ? "groomGiftlist" : "brideGiftlist");
    }

    public ItemSlot getGiftItem(Client c, boolean groom, int idx) {
        try {
            return getGiftItems(c, groom).get(idx);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    public void addGiftItem(boolean groom, ItemSlot item) {
        List<ItemSlot> gifts = getGiftItemsList(groom);
        synchronized (gifts) {
            gifts.add(item);
        }
    }

    public void removeGiftItem(boolean groom, ItemSlot item) {
        List<ItemSlot> gifts = getGiftItemsList(groom);
        synchronized (gifts) {
            gifts.remove(item);
        }
    }

    public Boolean isMarriageGroom(Character chr) {
        Boolean groom = null;
        try {
            int groomid = this.getIntProperty("groomId"), brideid = this.getIntProperty("brideId");
            if (chr.getId() == groomid) {
                groom = true;
            } else if (chr.getId() == brideid) {
                groom = false;
            }
        } catch (NumberFormatException nfe) {
        }

        return groom;
    }

    public static boolean claimGiftItems(Client c, Character chr) {
        List<ItemSlot> gifts = loadGiftItemsFromDb(c, chr.getId());
        if (Inventory.checkSpot(chr, gifts)) {
            try (Connection con = DatabaseConnection.getConnection()) {
                ItemFactory.MARRIAGE_GIFTS.saveItems(new LinkedList<>(), chr.getId(), con);
            } catch (SQLException sqle) {
                sqle.printStackTrace();
            }

            for (ItemSlot item : gifts) {
                InventoryManipulator.addFromDrop(chr.getClient(), item, false);
            }

            return true;
        }

        return false;
    }

    public static List<ItemSlot> loadGiftItemsFromDb(Client c, int cid) {
        List<ItemSlot> items = new LinkedList<>();

        try {
            for (Pair<ItemSlot, InventoryType> it : ItemFactory.MARRIAGE_GIFTS.loadItems(cid, false)) {
                items.add(it.getLeft());
            }
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }

        return items;
    }

    public void saveGiftItemsToDb(Client c, boolean groom, int cid) {
        Marriage.saveGiftItemsToDb(c, getGiftItems(c, groom), cid);
    }

    public static void saveGiftItemsToDb(Client c, List<ItemSlot> giftItems, int cid) {
        List<Pair<ItemSlot, InventoryType>> items = new LinkedList<>();
        for (ItemSlot it : giftItems) {
            items.add(new Pair<>(it, it.getInventoryType()));
        }

        try (Connection con = DatabaseConnection.getConnection()) {
            ItemFactory.MARRIAGE_GIFTS.saveItems(items, cid, con);
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }
    }
}
