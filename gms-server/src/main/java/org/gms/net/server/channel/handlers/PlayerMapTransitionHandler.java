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

package org.gms.net.server.channel.handlers;

import org.gms.client.EffectType;
import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.Collections;
import java.util.List;

/**
 * @author Ronan
 * 玩家完成切换地图触发
 */
public final class PlayerMapTransitionHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        // strand 迁移（doc/13 §18）：transitionComplete/homing beacon 是 player 状态，
        // 须在本会话 strand 上写；mob 视图重建跨 map 域，快照过界（isHidden）后 post
        // map shim 串行执行（mob 侧状态照旧并发语义，不属 player actor 范围）。
        return true;
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {

        Character chr = c.getPlayer();
        chr.setMapTransitionComplete();

        int beaconid = chr.getBuffSource(EffectType.HOMING_BEACON);
        if (beaconid != -1) {
            chr.cancelBuffStats(EffectType.HOMING_BEACON);

            final List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.HOMING_BEACON, 0));
            chr.sendPacket(PacketCreator.giveBuff(1, beaconid, stat));
        }

        if (chr.isHidden()) {  // thanks Lame (Conrad) for noticing hidden characters controlling mobs
            return;
        }
        final MapleMap map = chr.getMap();
        map.post("map-transitionMobView", () -> map.onTransitionMobView(chr.ref(), c));
    }
}
