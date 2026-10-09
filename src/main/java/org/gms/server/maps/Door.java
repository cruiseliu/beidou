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
package org.gms.server.maps;

import org.gms.client.character.Character;
import org.gms.client.character.CharacterRef;
import org.gms.client.character.MapView;
import org.gms.client.messages.MapObjectsViewMessage;
import org.gms.config.GameConfig;
import org.gms.net.server.services.task.channel.OverallService;
import org.gms.net.server.services.type.ChannelServices;
import org.gms.util.Pair;

import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * @author Matze
 * @author Ronan
 */
public class Door {
    private int ownerId;
    private MapleMap town;
    private Portal townPortal;
    private final MapleMap target;
    private Pair<String, Integer> posStatus = null;
    private long deployTime;
    private boolean active;

    private DoorObject townDoor;
    private DoorObject areaDoor;

    public Door(Character owner, Point targetPosition) {
        this.ownerId = owner.getId();
        this.target = owner.getMap();

        if (target.canDeployDoor(targetPosition)) {
            if (GameConfig.getServerBoolean("use_enforce_mystic_door_position")) {
                posStatus = target.getDoorPositionStatus(targetPosition);
            }

            if (posStatus == null) {
                this.town = this.target.getReturnMap();
                this.townPortal = getTownDoorPortal(owner.getDoorSlot());
                this.deployTime = System.currentTimeMillis();
                this.active = true;

                if (townPortal != null) {
                    this.areaDoor = new DoorObject(ownerId, town, target, townPortal.getId(), targetPosition, townPortal.getPosition());
                    this.townDoor = new DoorObject(ownerId, target, town, -1, townPortal.getPosition(), targetPosition);

                    this.areaDoor.setPairOid(this.townDoor.getObjectId());
                    this.townDoor.setPairOid(this.areaDoor.getObjectId());
                } else {
                    this.ownerId = -1;
                }
            } else {
                this.ownerId = -3;
            }
        } else {
            this.ownerId = -2;
        }
    }

    public void updateDoorPortal(Character owner) {
        int slot = owner.fetchDoorSlot();

        Portal nextTownPortal = getTownDoorPortal(slot);
        if (nextTownPortal != null) {
            townPortal = nextTownPortal;
            areaDoor.update(nextTownPortal.getId(), nextTownPortal.getPosition());
        }
    }

    private void broadcastRemoveDoor(Character owner) {
        DoorObject areaDoor = this.getAreaDoor();
        DoorObject townDoor = this.getTownDoor();

        MapleMap target = this.getTarget();
        MapleMap town = this.getTown();

        Collection<CharacterRef> targetChars = target.getCharacters();
        Collection<CharacterRef> townChars = town.getCharacters();

        target.removeMapObject(areaDoor);
        town.removeMapObject(townDoor);

        for (CharacterRef chrR : targetChars) {
            Character chr = chrR.unref();
            areaDoor.sendDestroyData(chr.getClient());
            // 可见集值注销（原活引用直写的值化回投；client 直发为既有 legacy 债，原样保留）
            chrR.post(new MapObjectsViewMessage(target.getId(), List.of(), List.of(areaDoor.getObjectId())));
        }

        for (CharacterRef chrR : townChars) {
            Character chr = chrR.unref();
            townDoor.sendDestroyData(chr.getClient());
            chrR.post(new MapObjectsViewMessage(town.getId(), List.of(), List.of(townDoor.getObjectId())));
        }

        owner.removePartyDoor(false);

        if (this.getTownPortal().getId() == 0x80) {
            for (CharacterRef chrR : townChars) {
                Character chr = chrR.unref();
                Door door = chr.getMainTownDoor();
                if (door != null) {
                    townDoor.sendSpawnData(chr.getClient());
                    chrR.post(new MapObjectsViewMessage(town.getId(),
                            List.of(new MapView.Entry(townDoor.getObjectId(), new MapView.MapObjectInfo(MapObjectType.DOOR, 0, townDoor.getPosition(), true))),
                            List.of()));
                }
            }
        }
    }

    public static void attemptRemoveDoor(final Character owner) {
        final Door destroyDoor = owner.getPlayerDoor();
        if (destroyDoor != null && destroyDoor.dispose()) {
            long effectTimeLeft = 3000 - destroyDoor.getElapsedDeployTime();   // portal deployment effect duration
            if (effectTimeLeft > 0) {
                MapleMap town = destroyDoor.getTown();

                OverallService service = (OverallService) town.getChannelServer().getServiceAccess(ChannelServices.OVERALL);
                service.registerOverallAction(town.getId(), () -> {
                    destroyDoor.broadcastRemoveDoor(owner);   // thanks BHB88 for noticing doors crashing players when instantly cancelling buff
                }, effectTimeLeft);
            } else {
                destroyDoor.broadcastRemoveDoor(owner);
            }
        }
    }

    private Portal getTownDoorPortal(int doorid) {
        return town.getDoorPortal(doorid);
    }

    public int getOwnerId() {
        return ownerId;
    }

    public DoorObject getTownDoor() {
        return townDoor;
    }

    public DoorObject getAreaDoor() {
        return areaDoor;
    }

    public MapleMap getTown() {
        return town;
    }

    public Portal getTownPortal() {
        return townPortal;
    }

    public MapleMap getTarget() {
        return target;
    }

    public Pair<String, Integer> getDoorStatus() {
        return posStatus;
    }

    public long getElapsedDeployTime() {
        return System.currentTimeMillis() - deployTime;
    }

    private boolean dispose() {
        if (active) {
            active = false;
            return true;
        } else {
            return false;
        }
    }

    public boolean isActive() {
        return active;
    }
}
