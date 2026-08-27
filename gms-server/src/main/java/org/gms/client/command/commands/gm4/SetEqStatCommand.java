/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
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

/*
   @Author: Arthur L - Refactored command content into modules
*/
package org.gms.client.command.commands.gm4;

import org.gms.client.inventory.ItemSlot;
import org.gms.client.character.Stat;
import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.util.I18nUtil;
import org.gms.client.inventory.ItemFlag;

public class SetEqStatCommand extends Command {
    {
        setDescription(I18nUtil.getMessage("SetEqStatCommand.message1"));
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage(I18nUtil.getMessage("SetEqStatCommand.message2"));
            return;
        }

        short newStat = (short) Math.max(0, Integer.parseInt(params[0]));
        short newSpdJmp = params.length >= 2 ? (short) Integer.parseInt(params[1]) : 0;
        InventoryTab equip = player.getInventory(InventoryType.EQUIP);

        for (byte i = 1; i <= equip.getSlotLimit(); i++) {
            try {
                ItemSlot eqItem = equip.getItem(i);
                Equip eq = eqItem.getEquipInfo();
                if (eq == null) {
                    continue;
                }

                eq.setStat(Stat.P_DEF, newStat);
                eq.setStat(Stat.ACCURACY, newStat);
                eq.setStat(Stat.AVOIDABILITY, newStat);
                eq.setStat(Stat.JUMP, newSpdJmp);
                eq.setStat(Stat.M_ATK, newStat);
                eq.setStat(Stat.M_DEF, newStat);
                eq.setStat(Stat.MAX_HP, newStat);
                eq.setStat(Stat.MAX_MP, newStat);
                eq.setStat(Stat.SPEED, newSpdJmp);
                eq.setStat(Stat.P_ATK, newStat);
                eq.setStat(Stat.DEX, newStat);
                eq.setStat(Stat.INT, newStat);
                eq.setStat(Stat.STR, newStat);
                eq.setStat(Stat.LUK, newStat);

                eqItem.addFlag(ItemFlag.UNTRADEABLE);

                player.forceUpdateItem(eqItem);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
