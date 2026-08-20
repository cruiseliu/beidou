package org.gms.client.character;

import org.gms.net.server.world.Party;
import org.gms.server.maps.Door;
import org.gms.server.maps.DoorObject;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 神秘门模块组件：个人门（pdoor）+ 队伍门槽位（doorSlot）+ 门的部署/移除/门更新编排。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getDoors/applyPartyDoor/... 对外转发）。
 *
 * 边界：只承载门语义——个人门、队伍门（party door）、门的部署与移除、组队操作后的门同步。
 * 队伍引用经 owner.party 访问（同包）；依赖经 owner 门面调用（sendPacket/getClient/...）。
 */
class CharacterMysticDoor {
    private final Character owner;

    /** 个人门（神秘门技能部署） */
    private Door pdoor = null;

    /** 队伍门槽位（party door 域；-1 未部署） */
    byte doorSlot = -1;

    CharacterMysticDoor(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    boolean canDoor() {
        Door door = getPlayerDoor();
        return door == null || (door.isActive() && door.getElapsedDeployTime() > 5000);
    }

    Collection<Door> getDoors() {
        owner.party.lock.lock();
        try {
            return (owner.party.party != null ? Collections.unmodifiableCollection(owner.party.party.getDoors().values()) : (pdoor != null ? Collections.singleton(pdoor) : new LinkedHashSet<>()));
        } finally {
            owner.party.lock.unlock();
        }
    }

    Door getPlayerDoor() {
        owner.party.lock.lock();
        try {
            return pdoor;
        } finally {
            owner.party.lock.unlock();
        }
    }

    Door getMainTownDoor() {
        for (Door door : getDoors()) {
            if (door.getTownPortal().getId() == 0x80) {
                return door;
            }
        }

        return null;
    }

    int getDoorSlot() {
        if (doorSlot != -1) {
            return doorSlot;
        }
        return fetchDoorSlot();
    }

    int fetchDoorSlot() {
        owner.party.lock.lock();
        try {
            doorSlot = (owner.party.party == null) ? 0 : owner.party.party.getPartyDoor(owner.getId());
            return doorSlot;
        } finally {
            owner.party.lock.unlock();
        }
    }

    // ── 部署/移除 ──

    void applyPartyDoor(Door door, boolean partyUpdate) {
        Party chrParty;
        owner.party.lock.lock();
        try {
            if (!partyUpdate) {
                pdoor = door;
            }

            chrParty = owner.getParty();
            if (chrParty != null) {
                chrParty.addDoor(owner.getId(), door);
            }
        } finally {
            owner.party.lock.unlock();
        }

        owner.party.silentPartyUpdateInternal(chrParty);
    }

    Door removePartyDoor(boolean partyUpdate) {
        Door ret = null;
        Party chrParty;

        owner.party.lock.lock();
        try {
            chrParty = owner.getParty();
            if (chrParty != null) {
                chrParty.removeDoor(owner.getId());
            }

            if (!partyUpdate) {
                ret = pdoor;
                pdoor = null;
            }
        } finally {
            owner.party.lock.unlock();
        }

        owner.party.silentPartyUpdateInternal(chrParty);
        return ret;
    }

    void removePartyDoor(Party formerParty) {    // 玩家已不在该队伍注册
        formerParty.removeDoor(owner.getId());
    }

    // ── 组队门同步（partyOperationUpdate 编排调用） ──

    private static void addPartyPlayerDoor(Character target) {
        Door targetDoor = target.getPlayerDoor();
        if (targetDoor != null) {
            target.applyPartyDoor(targetDoor, true);
        }
    }

    private static void removePartyPlayerDoor(Party party, Character target) {
        party.removeDoor(target.getId());
    }

    static void updatePartyTownDoors(Party party, Character target, Character partyLeaver, List<Character> partyMembers) {
        if (partyLeaver != null) {
            removePartyPlayerDoor(party, target);
        } else {
            addPartyPlayerDoor(target);
        }

        Map<Integer, Door> partyDoors = null;
        if (!partyMembers.isEmpty()) {
            partyDoors = party.getDoors();

            for (Character pchr : partyMembers) {
                Door door = partyDoors.get(pchr.getId());
                if (door != null) {
                    door.updateDoorPortal(pchr);
                }
            }

            for (Door door : partyDoors.values()) {
                for (Character pchar : partyMembers) {
                    DoorObject mdo = door.getTownDoor();
                    mdo.sendDestroyData(pchar.getClient(), true);
                    pchar.removeVisibleMapObject(mdo);
                }
            }

            if (partyLeaver != null) {
                Collection<Door> leaverDoors = partyLeaver.getDoors();
                for (Door door : leaverDoors) {
                    for (Character pchar : partyMembers) {
                        DoorObject mdo = door.getTownDoor();
                        mdo.sendDestroyData(pchar.getClient(), true);
                        pchar.removeVisibleMapObject(mdo);
                    }
                }
            }

            List<Integer> histMembers = party.getMembersSortedByHistory();
            for (Integer chrid : histMembers) {
                Door door = partyDoors.get(chrid);

                if (door != null) {
                    for (Character pchar : partyMembers) {
                        DoorObject mdo = door.getTownDoor();
                        mdo.sendSpawnData(pchar.getClient());
                        pchar.addVisibleMapObject(mdo);
                    }
                }
            }
        }

        if (partyLeaver != null) {
            Collection<Door> leaverDoors = partyLeaver.getDoors();

            if (partyDoors != null) {
                for (Door door : partyDoors.values()) {
                    DoorObject mdo = door.getTownDoor();
                    mdo.sendDestroyData(partyLeaver.getClient(), true);
                    partyLeaver.removeVisibleMapObject(mdo);
                }
            }

            for (Door door : leaverDoors) {
                DoorObject mdo = door.getTownDoor();
                mdo.sendDestroyData(partyLeaver.getClient(), true);
                partyLeaver.removeVisibleMapObject(mdo);
            }

            for (Door door : leaverDoors) {
                door.updateDoorPortal(partyLeaver);

                DoorObject mdo = door.getTownDoor();
                mdo.sendSpawnData(partyLeaver.getClient());
                partyLeaver.addVisibleMapObject(mdo);
            }
        }
    }
}
