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
package org.gms.client.command.commands.gm2;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.command.Command;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.util.I18nUtil;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 发放宠物：@pet ITEM_ID [LIFE]。LIFE 以 d/h/m/s 单位字母结尾，无单位视为天；
 * 缺省时由 wz info/life 决定（Pet.create(itemId)）。
 */
public class PetCommand extends Command {
    {
        setDescription(I18nUtil.getMessage("PetCommand.message1"));
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        if (params.length < 1) {
            player.yellowMessage(I18nUtil.getMessage("PetCommand.message2"));
            return;
        }

        int itemId;
        try {
            itemId = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            player.yellowMessage(I18nUtil.getMessage("PetCommand.message2"));
            return;
        }

        if (ItemInformationProvider.getInstance().getName(itemId) == null || !ItemConstants.isPet(itemId)) {
            player.yellowMessage(I18nUtil.getMessage("PetCommand.message3", params[0]));
            return;
        }

        if (params.length >= 2) {
            long durationMs;
            try {
                durationMs = parseLife(params[1]);
            } catch (NumberFormatException e) {
                player.yellowMessage(I18nUtil.getMessage("PetCommand.message2"));
                return;
            }
            player.getPets().grantPet(itemId, durationMs);
        } else {
            player.getPets().grantPet(itemId);
        }
    }

    /** LIFE 词法：[数字][d/h/m/s]，无单位 = 天；非法数字抛 NumberFormatException。 */
    private static long parseLife(String s) {
        String lower = s.toLowerCase();
        long value = Long.parseLong(lower.substring(0, lower.length() - 1));
        return switch (lower.charAt(lower.length() - 1)) {
            case 'd' -> DAYS.toMillis(value);
            case 'h' -> HOURS.toMillis(value);
            case 'm' -> MINUTES.toMillis(value);
            case 's' -> SECONDS.toMillis(value);
            default -> DAYS.toMillis(Long.parseLong(lower));
        };
    }
}
