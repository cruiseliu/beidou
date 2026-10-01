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

import org.gms.client.character.Stat;
import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemFlag;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.util.I18nUtil;

public class ProItemCommand extends Command {
    {
        setDescription(I18nUtil.getMessage("ProItemCommand.message1"));
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage(I18nUtil.getMessage("ProItemCommand.message2"));
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int itemid = Integer.parseInt(params[0]);

        if (ii.getName(itemid) == null) {
            player.yellowMessage(I18nUtil.getMessage("ProItemCommand.message3", params[0]));
            return;
        }

        short stat = (short) Math.max(0, Short.parseShort(params[1]));
        short spdjmp = params.length >= 3 ? (short) Math.max(0, Short.parseShort(params[2])) : 0;

        InventoryType type = ItemConstants.getInventoryType(itemid);
        if (type.equals(InventoryType.EQUIP)) {
            ItemSlot it = ii.getEquipById(itemid);
            it.setOwner(player.getName());

            hardsetItemStats(it.getItem(), stat, spdjmp);
            InventoryManipulator.addFromDrop(c, it);
        } else {
            player.dropMessage(6, I18nUtil.getMessage("ProItemCommand.message4"));
        }
    }

    private static void hardsetItemStats(Item item, short stat, short spdjmp) {
        Equip equip = item.getEquipInfo();
        equip.setStat(Stat.STR, stat);
        equip.setStat(Stat.DEX, stat);
        equip.setStat(Stat.INT, stat);
        equip.setStat(Stat.LUK, stat);
        equip.setStat(Stat.M_ATK, stat);
        equip.setStat(Stat.P_ATK, stat);
        equip.setStat(Stat.ACCURACY, stat);
        equip.setStat(Stat.AVOIDABILITY, stat);
        equip.setStat(Stat.JUMP, spdjmp);
        equip.setStat(Stat.SPEED, spdjmp);
        equip.setStat(Stat.P_DEF, stat);
        equip.setStat(Stat.M_DEF, stat);
        equip.setStat(Stat.MAX_HP, stat);
        equip.setStat(Stat.MAX_MP, stat);

        item.addFlag(ItemFlag.UNTRADEABLE);
    }
}
