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
package org.gms.net.server.handlers.login;

import org.gms.client.character.Character;
import org.gms.client.character.CharacterView;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.util.PacketCreator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public final class ViewAllCharHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        return true;   // strand 迁移 M2-2a：登录/世界入口链路（charlist 装载、世界入口派发）
    }

    private static final int CHARACTER_LIMIT = 60; // Client will crash if sending 61 or more characters

    @Override
    public final void handlePacket(InPacket p, Client c) {
        try {
            if (!c.canRequestCharlist()) {   // client breaks if the charlist request pops too soon
                c.sendPacket(PacketCreator.showAllCharacter(0, 0));
                return;
            }

            SortedMap<Integer, List<CharacterView>> worldChrs = Server.getInstance().loadAccountCharlist(c.getAccID(), c.getVisibleWorlds());
            worldChrs = limitTotalChrs(worldChrs, CHARACTER_LIMIT);

            padChrsIfNeeded(worldChrs);

            int totalWorlds = worldChrs.size();
            int totalChrs = countTotalChrs(worldChrs);
            c.sendPacket(PacketCreator.showAllCharacter(totalWorlds, totalChrs));

            final boolean usePic = GameConfig.getServerBoolean("enable_pic") && !c.canBypassPic();
            worldChrs.forEach((worldId, chrs) ->
                    c.sendPacket(PacketCreator.showAllCharacterInfo(worldId, chrs, usePic))
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static SortedMap<Integer, List<CharacterView>> limitTotalChrs(SortedMap<Integer, List<CharacterView>> worldChrs,
                                                                      int limit) {
        if (countTotalChrs(worldChrs) <= limit) {
            return worldChrs;
        } else {;
            return cutAfterChrLimit(worldChrs, limit);
        }
    }

    private static int countTotalChrs(Map<Integer, List<CharacterView>> worldChrs) {
        return worldChrs.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private static SortedMap<Integer, List<CharacterView>> cutAfterChrLimit(SortedMap<Integer, List<CharacterView>> worldChrs,
                                                                        int limit) {
        SortedMap<Integer, List<CharacterView>> cappedCopy = new TreeMap<>();
        int runningChrTotal = 0;
        for (Map.Entry<Integer, List<CharacterView>> entry : worldChrs.entrySet()) {
            int worldId = entry.getKey();
            List<CharacterView> chrs = entry.getValue();
            if (runningChrTotal + chrs.size() <= limit) { // Limit not reached, move them all
                runningChrTotal += chrs.size();
                cappedCopy.put(worldId, chrs);
            } else { // Limit would be reached if all chrs were moved. Move just enough to fit within limit.
                int remainingSlots = limit - runningChrTotal;
                List<CharacterView> lastChrs = chrs.subList(0, remainingSlots);
                cappedCopy.put(worldId, lastChrs);
                break;
            }
        }

        return cappedCopy;
    }

    /**
     * If there are more characters than fits the screen (9), and you start scrolling down,
     * the characters on the last row will not appear unless the row is completely filled.
     * Meaning, if there are 1 or 2 characters remaining on the last row, they will not appear.
     *
     * @param totalChrs total amount of characters to display on 'View all characters' screen
     * @return if we need to pad the last row to include the characters that would otherwise not appear
     */
    private static void padChrsIfNeeded(SortedMap<Integer, List<CharacterView>> worldChrs) {
        while (shouldPadLastRow(countTotalChrs(worldChrs))) {
            final List<CharacterView> lastWorldChrs = getLastWorldChrs(worldChrs);
            final CharacterView lastChrForPadding = getLastItem(lastWorldChrs);
            lastWorldChrs.add(lastChrForPadding);
        }
    }

    private static boolean shouldPadLastRow(int totalChrs) {
        boolean shouldScroll = totalChrs > 9;
        boolean isLastRowFilled = totalChrs % 3 == 0;
        return shouldScroll && !isLastRowFilled;
    }

    private static List<CharacterView> getLastWorldChrs(SortedMap<Integer, List<CharacterView>> worldChrs) {
        return worldChrs.get(worldChrs.lastKey());
    }

    private static <T> T getLastItem(List<T> list) {
        Objects.requireNonNull(list);
        return list.get(list.size() - 1);
    }
}
