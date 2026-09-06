/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

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
package org.gms.net.server.channel.handlers;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.pet.Pet;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.scripting.item.ItemScript;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PetFoodHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        return true;   // strand 迁移 M1-S1：宠物域入口（见 doc 设计，宠物子系统 strand 独占）
    }

    private static final Logger log = LoggerFactory.getLogger(UseCashItemHandler.class);

    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        AutobanManager abm = chr.getAutoBanManager();
        if (abm.getLastSpam(2) + 500 > currentServerTime()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        abm.spam(2);
        p.readInt(); // timestamp issue detected thanks to Masterrulax
        abm.setTimestamp(1, Server.getInstance().getCurrentTimestamp(), 3);
        if (!chr.hasSummonedPet()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        int previousFullness = 100;
        byte slot = 0;
        Pet[] pets = chr.LEGACY_getSummonSlots();
        for (byte i = 0; i < 3; i++) {
            if (pets[i] != null) {
                if (pets[i].getFullness() < previousFullness) {
                    slot = i;
                    previousFullness = pets[i].getFullness();
                }
            }
        }

        Pet pet = chr.getPet(slot);
        if (pet == null) {
            return;
        }

        short pos = p.readShort();
        int itemId = p.readInt();

        ItemScript script = ItemScript.forItem(itemId);
        if (script == null || !script.hasHook(chr, ItemScript.HOOK_USE)) {
            log.error("Pet food {} missing onUse script", itemId);
            c.enableActions();
            return;
        }

        if (c.tryacquireClient()) {
            try {
                InventoryTab useInv = chr.getInventory(InventoryType.USE);
                useInv.lockInventory();
                try {
                ItemSlot itemSlot = useInv.getItem(pos);
                if (itemSlot.getItemId() != itemId) {
                    log.error("Mismatch item: character:{} slot:{} real:{} packet:{}", chr.getId(), pos, itemSlot.getItemId(), itemId);
                    c.enableActions();
                    return;
                }

                // 合并域包住 hook + 消耗（与 UseCashItemHandler 的 hook 分支同款）：
                // 一次喂食的全部语义更新一次 flush
                try (var update = chr.getRemote().update()) {
                    boolean success = script.invokeUse(chr, itemSlot.getItem());
                    if (!success) {
                        c.enableActions();
                        return;
                    }

                    // pet.gainTamenessFullness(chr, (pet.getFullness() <= 75) ? 1 : 0, 30, 1);   // 25+ "emptyness" to get +1 tameness
                    InventoryManipulator.removeFromSlot(c, InventoryType.USE, pos, (short) 1, false);
                }
                } finally {
                    useInv.unlockInventory();
                }

            } finally {
                c.releaseClient();
            }
        }
    }
}
