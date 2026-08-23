package org.gms.client.character;

import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 组队模块组件：队伍引用（party）+ 队伍成员查询 + 组队操作（入队/退队/静默更新/HP 广播）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getParty/getPartyMembers/... 对外转发）。
 *
 * 边界：只承载组队语义——队伍引用、成员查询、组队操作与封包。
 * 组队任务（party quest）与队伍门（party door，pdoor/doorSlot/applyPartyDoor 等）不属本组件，
 * 留在 Character；partyOperationUpdate 中的门更新编排经 owner 门面调用；
 * 依赖经 owner 门面调用（getMap/sendPacket/getClient/getCurrentMaxHp/getHp/...）。
 */
class CharacterParty {
    private final Character owner;

    /** 队伍引用（lock 保护；锁序：lock 最先获取） */
    Party party;
    /** 队伍成员视图（null 表示不在队） */
    PartyCharacter mpc = null;
    /** 队伍锁（原 Character.prtLock） */
    final Lock lock = new ReentrantLock();

    /** 是否接受组队邀请（party search；CharacterJob.changeJob 读取） */
    boolean canRecvPartySearchInvite = true;

    CharacterParty(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    Party getParty() {
        lock.lock();
        try {
            return party;
        } finally {
            lock.unlock();
        }
    }

    int getPartyId() {
        lock.lock();
        try {
            return (party != null ? party.getId() : -1);
        } finally {
            lock.unlock();
        }
    }

    List<Character> getPartyMembersOnline() {
        List<Character> list = new LinkedList<>();

        lock.lock();
        try {
            if (party != null) {
                for (PartyCharacter mpc : party.getMembers()) {
                    Character mc = mpc.getPlayer();
                    if (mc != null) {
                        list.add(mc);
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        return list;
    }

    List<Character> getPartyMembersOnSameMap() {
        List<Character> list = new LinkedList<>();
        int thisMapHash = owner.getMap().hashCode();

        lock.lock();
        try {
            if (party != null) {
                for (PartyCharacter mpc : party.getMembers()) {
                    Character chr = mpc.getPlayer();
                    if (chr != null) {
                        MapleMap chrMap = chr.getMap();
                        if (chrMap != null && chrMap.hashCode() == thisMapHash && chr.isLoggedInWorld()) {
                            list.add(chr);
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        return list;
    }

    boolean isPartyMember(Character chr) {
        return isPartyMember(chr.getId());
    }

    boolean isPartyMember(int cid) {
        lock.lock();
        try {
            if (party != null) {
                return party.getMemberById(cid) != null;
            }
        } finally {
            lock.unlock();
        }

        return false;
    }

    boolean isPartyLeader() {
        lock.lock();
        try {
            return party != null && party.getLeaderId() == owner.getId();
        } finally {
            lock.unlock();
        }
    }

    PartyCharacter getMPC() {
        if (mpc == null) {
            mpc = new PartyCharacter(owner);
        }
        return mpc;
    }

    void setMPC(PartyCharacter mpc) {
        this.mpc = mpc;
    }

    // ── 组队操作 ──

    void setParty(Party p) {
        lock.lock();
        try {
            if (p == null) {
                this.mpc = null;
                owner.door.doorSlot = -1;

                party = null;
            } else {
                party = p;
            }
        } finally {
            lock.unlock();
        }
    }

    boolean leaveParty() {
        Party party;
        boolean partyLeader;

        lock.lock();
        try {
            party = this.party;
            partyLeader = isPartyLeader();
        } finally {
            lock.unlock();
        }

        if (party != null) {
            if (partyLeader) {
                party.assignNewLeader(owner.client);
            }
            Party.leaveParty(party, owner.client);

            return true;
        } else {
            return false;
        }
    }

    void silentPartyUpdate() {
        silentPartyUpdateInternal(getParty());
    }

    void silentPartyUpdateInternal(Party chrParty) {    // 包内可见：CharacterMap.changeMapInternal 调用
        if (chrParty != null) {
            owner.getWorldServer().updateParty(chrParty.getId(), PartyOperation.SILENT_UPDATE, getMPC());
        }
    }

    void updatePartyMemberHP() {
        lock.lock();
        try {
            updatePartyMemberHPInternal();
        } finally {
            lock.unlock();
        }
    }

    void updatePartyMemberHPInternal() {    // 包内可见：CharacterMap.changeMapInternal 调用
        if (party != null) {
            int curmaxhp = owner.getCurrentMaxHp();
            int curhp = owner.getHp();
            for (Character partychar : this.getPartyMembersOnSameMap()) {
                partychar.sendPacket(PacketCreator.updatePartyMemberHP(owner.getId(), curhp, curmaxhp));
            }
        }
    }

    void receivePartyMemberHP() {
        // 不在此处包一层 lock:getPartyMembersOnSameMap 内部已持 lock 保护 party 引用。
        // 若再包一层,会在持 lock 的同时对同图队友逐个取 getHp()(对方 stats.rLock),
        // 与 recalcAndSyncParty(本角色 stats.wLock 后再 updatePartyMemberHP 取 lock)形成
        // 跨角色反向锁顺序,存在死锁窗口。
        for (Character partychar : this.getPartyMembersOnSameMap()) {
            owner.sendPacket(PacketCreator.updatePartyMemberHP(partychar.getId(), partychar.getHp(), partychar.getCurrentMaxHp()));
        }
    }

    // ── 组队邀请（party search） ──

    void updatePartySearchAvailability(boolean pSearchAvailable) {
        if (pSearchAvailable) {
            if (canRecvPartySearchInvite && getParty() == null) {
                owner.getWorldServer().getPartySearchCoordinator().attachPlayer(owner);
            }
        } else {
            if (canRecvPartySearchInvite) {
                owner.getWorldServer().getPartySearchCoordinator().detachPlayer(owner);
            }
        }
    }

    boolean toggleRecvPartySearchInvite() {
        canRecvPartySearchInvite = !canRecvPartySearchInvite;

        if (canRecvPartySearchInvite) {
            updatePartySearchAvailability(getParty() == null);
        } else {
            owner.getWorldServer().getPartySearchCoordinator().detachPlayer(owner);
        }

        return canRecvPartySearchInvite;
    }

    boolean isRecvPartySearchInviteEnabled() {
        return canRecvPartySearchInvite;
    }

    void setCanRecvPartySearchInvite(boolean canRecvPartySearchInvite) {
        this.canRecvPartySearchInvite = canRecvPartySearchInvite;
    }

    // ── 组队操作更新（含掉落归属；门更新编排经 owner 门面） ──

    void partyOperationUpdate(Party party, List<Character> exPartyMembers) {
        List<WeakReference<MapleMap>> mapIds = owner.map.getLastVisitedMaps();

        List<Character> partyMembers = new LinkedList<>();
        for (Character mc : (exPartyMembers != null) ? exPartyMembers : this.getPartyMembersOnline()) {
            if (mc.isLoggedInWorld()) {
                partyMembers.add(mc);
            }
        }

        Character partyLeaver = null;
        if (exPartyMembers != null) {
            partyMembers.remove(owner);
            partyLeaver = owner;
        }

        MapleMap map = owner.getMap();
        List<MapItem> partyItems = null;

        int partyId = exPartyMembers != null ? -1 : this.getPartyId();
        for (WeakReference<MapleMap> mapRef : mapIds) {
            MapleMap mapObj = mapRef.get();

            if (mapObj != null) {
                List<MapItem> partyMapItems = mapObj.updatePlayerItemDropsToParty(partyId, owner.getId(), partyMembers, partyLeaver);
                if (map.hashCode() == mapObj.hashCode()) {
                    partyItems = partyMapItems;
                }
            }
        }

        if (partyItems != null && exPartyMembers == null) {
            map.updatePartyItemDropsToNewcomer(owner, partyItems);
        }

        CharacterMysticDoor.updatePartyTownDoors(party, owner, partyLeaver, partyMembers);
    }
}
