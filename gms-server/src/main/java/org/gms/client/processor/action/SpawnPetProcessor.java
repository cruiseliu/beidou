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
package org.gms.client.processor.action;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemPool;
import org.gms.client.pet.Pet;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;

/**
 * @author RonanLana - just added locking on OdinMS' SpawnPetHandler method body
 */
public class SpawnPetProcessor {
    private static final Logger log = LoggerFactory.getLogger(Inventory.class);

    public static void processSpawnPet(Client c, byte slot, boolean lead) {
        if (c.tryacquireClient()) {
            try {
                Character chr = c.getPlayer();
                Inventory inv = chr.getInventory();

                Pet pet = chr.getPetById(chr.getInventory(InventoryType.CASH).getItem(slot).getPetId());
                if (pet == null) {
                    return;
                }

                Item petItem = chr.findPetItemSlot(pet.getPetId()).getItem();

                if (pet.isEgg()) {
                    // 蛋类道具不可召唤（官方语义）：客户端以 SPAWN_PET 表达"使用"——孵化 = 换宿主物品
                    ItemPool pool = pet.getEvolvePool();
                    boolean success = inv.tryUpdate()  // will trigger onLeave/EnterInventory hooks
                            .remove(petItem, 1)
                            .addPoolAndCommit(pool);
                    if (!success) {
                        // this should never happen
                        log.error("Failed to hatch pet {} (item:{})", pet.getPetId(), pet.getItemId());
                        chr.dropMessage(5, "无法孵化，请检查背包空间。");
                    }
                    pet.evolve(chr.findPetItemSlot(pet.getPetId()).getItemId());
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }
                if (!pet.isAlive()) {
                    c.sendPacket(PacketCreator.enableActions());   // 失活宠物不可召唤（doc/11 §7）
                    return;
                }
                if (chr.getPetIndex(pet) != -1) {
                    pet.desummon();
                } else {
                    if (chr.getSkillLevel(8) == 0 && chr.getPet(0) != null) {
                        chr.getPet(0).desummon();
                    }
                    if (lead) {
                        chr.shiftPetsRight();
                    }
                    Point pos = chr.getPosition();
                    pos.y -= 12;
                    int fh = chr.getMap().getFootholds().findBelow(pet.getPos()).getId();

                    pet.summon(pos, fh);

                    c.sendPacket(PacketCreator.enableActions());

                    chr.commitExcludedItems();
                }
            } finally {
                c.releaseClient();
            }
        }
    }
}
