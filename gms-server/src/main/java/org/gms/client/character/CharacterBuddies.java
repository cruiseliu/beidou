package org.gms.client.character;

import org.gms.client.BuddyList;
import org.gms.client.BuddylistEntry;
import org.gms.client.CharacterNameAndId;
import org.gms.client.Client;
import org.gms.util.PacketCreator;

/**
 * 好友模块组件：好友列表（buddylist）+ 好友操作（删除/扩容/请求队列）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getBuddylist/deleteBuddy/setBuddyCapacity/... 对外转发）。
 *
 * 边界：只承载好友语义——好友列表、好友容量、删除好友、好友请求转发。
 * 持久化 SQL（buddies 表）留在 Character.saveCharToDB，数据访问经组件；
 * 依赖经 owner 门面调用（sendPacket/getWorldServer/...）。
 */
class CharacterBuddies {
    private final Character owner;

    /** 好友列表 */
    private BuddyList buddylist;

    CharacterBuddies(Character owner) {
        this.owner = owner;
        this.buddylist = new BuddyList(20);
    }

    // ── 查询 ──

    BuddyList getBuddylist() {
        return buddylist;
    }

    void setBuddylist(BuddyList buddylist) {
        this.buddylist = buddylist;
    }

    // ── 操作 ──

    void deleteBuddy(int otherCid) {
        BuddyList bl = getBuddylist();

        if (bl.containsVisible(otherCid)) {
            notifyRemoteChannel(owner.client, owner.getWorldServer().find(otherCid), otherCid, BuddyList.BuddyOperation.DELETED);
        }
        bl.remove(otherCid);
        owner.sendPacket(PacketCreator.updateBuddylist(getBuddylist().getBuddies()));
        nextPendingRequest(owner.client);
    }

    void setBuddyCapacity(int capacity) {
        buddylist.setCapacity(capacity);
        owner.sendPacket(PacketCreator.updateBuddyCapacity(capacity));
    }

    private void nextPendingRequest(Client c) {
        CharacterNameAndId pendingBuddyRequest = c.getPlayer().getBuddylist().pollPendingRequest();
        if (pendingBuddyRequest != null) {
            c.sendPacket(PacketCreator.requestBuddylistAdd(pendingBuddyRequest.getId(), c.getPlayer().getId(), pendingBuddyRequest.getName()));
        }
    }

    private void notifyRemoteChannel(Client c, int remoteChannel, int otherCid, BuddyList.BuddyOperation operation) {
        Character player = c.getPlayer();
        if (remoteChannel != -1) {
            c.getWorldServer().buddyChanged(otherCid, player.getId(), player.getName(), c.getChannel(), operation);
        }
    }
}
