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
import org.gms.client.SkillFactory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.pet.Pet;
import org.gms.client.pet.PetDataFactory;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.util.PacketCreator;

import java.awt.*;

/**
 * @author RonanLana - just added locking on OdinMS' SpawnPetHandler method body
 */
public class SpawnPetProcessor {
    public static void processSpawnPet(Client c, byte slot, boolean lead) {
        if (c.tryacquireClient()) {
            try {
                Character chr = c.getPlayer();
                Pet pet = chr.getPetById(chr.getInventory(InventoryType.CASH).getItem(slot).getPetId());
                if (pet == null) {
                    return;
                }

                int evolveid = PetDataFactory.getEvolution(pet.getItemId());
                if (evolveid > 0) {
                    // 蛋类道具不可召唤（官方语义）：客户端以 SPAWN_PET 表达"使用"——孵化 = 换宿主物品
                    if (!chr.getPets().evolvePet(pet.getUniqueId(), evolveid)) {
                        chr.dropMessage(5, "无法孵化，请检查背包空间。");
                    }
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }
                if (chr.getPetIndex(pet) != -1) {
                    chr.unEquipPet(pet, true);
                } else {
                    if (chr.getSkillLevel(8) == 0 && chr.getPet(0) != null) {
                        chr.unEquipPet(chr.getPet(0), false);
                    }
                    if (lead) {
                        chr.shiftPetsRight();
                    }
                    Point pos = chr.getPosition();
                    pos.y -= 12;
                    pet.setPos(pos);
                    int fh = chr.getMap().getFootholds().findBelow(pet.getPos()).getId();
                    pet.setStance(0);
                    pet.setSummoned(true);
                    pet.saveToDb();
                    chr.addPet(pet);
                    // 登录时未召唤的宠物不会预加载过滤配置，这里补载后再同步给客户端。
                    chr.loadPetExcludedItems(pet.getUniqueId());
                    chr.getRemote().pet().summonPet(chr, pet, fh);
                    c.sendPacket(PacketCreator.enableActions());

                    chr.commitExcludedItems();
                    chr.getClient().getWorldServer().registerPetHunger(chr, chr.getPetIndex(pet));
                }
            } finally {
                c.releaseClient();
            }
        }
    }
}
