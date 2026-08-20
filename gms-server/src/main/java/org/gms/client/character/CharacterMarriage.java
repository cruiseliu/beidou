package org.gms.client.character;

import org.gms.client.Ring;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MapId;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.Marriage;
import org.gms.util.packets.WeddingPackets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 婚姻/戒指模块组件：结婚戒指（marriageRing）+ 伴侣（partnerId）+ 求婚/友情戒指列表 + 婚姻实例。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（isMarried/getMarriageRing/addPlayerRing/... 对外转发）。
 *
 * 边界：只承载婚姻/戒指语义——戒指集合、伴侣关系、婚姻实例与伴侣换图通知。
 * 结婚物品 id（marriageItemId，characters 表列）随本组件持久化；
 * 依赖经 owner 门面调用（getEventInstance/getWorldServer/getId/getMapId/...）。
 */
class CharacterMarriage {
    private final Character owner;

    /** 结婚戒指（装备中） */
    private Ring marriageRing;
    /** 结婚物品 id（持久化到 characters.marriageItemId） */
    private int marriageItemId = -1;
    /** 婚姻伴侣角色 id（持久化到 characters.partnerId） */
    private int partnerId = -1;
    /** 求婚戒指列表 */
    private final List<Ring> crushRings = new ArrayList<>();
    /** 友情戒指列表 */
    private final List<Ring> friendshipRings = new ArrayList<>();

    CharacterMarriage(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    Ring getMarriageRing() {
        return partnerId > 0 ? marriageRing : null;
    }

    void setMarriageRing(Ring marriageRing) {
        this.marriageRing = marriageRing;
    }

    int getMarriageItemId() {
        return marriageItemId;
    }

    void setMarriageItemId(int marriageItemId) {
        this.marriageItemId = marriageItemId;
    }

    int getPartnerId() {
        return partnerId;
    }

    void setPartnerId(int partnerId) {
        this.partnerId = partnerId;
    }

    List<Ring> getCrushRings() {
        synchronized (crushRings) {
            Collections.sort(crushRings);
            return new ArrayList<>(crushRings);
        }
    }

    List<Ring> getFriendshipRings() {
        synchronized (friendshipRings) {
            Collections.sort(friendshipRings);
            return new ArrayList<>(friendshipRings);
        }
    }

    Ring getRingById(int id) {
        Optional<Ring> ringOptional = getCrushRings().stream().filter(ring -> ring.getRingId() == id).findFirst();
        if (ringOptional.isPresent()) {
            return ringOptional.get();
        }
        ringOptional = getFriendshipRings().stream().filter(ring -> ring.getRingId() == id).findFirst();
        if (ringOptional.isPresent()) {
            return ringOptional.get();
        }
        if (marriageRing != null && marriageRing.getRingId() == id) {
            return marriageRing;
        }
        return null;
    }

    int getRelationshipId() {
        return owner.getWorldServer().getRelationshipId(owner.getId());
    }

    boolean isMarried() {
        return marriageRing != null && partnerId > 0;
    }

    boolean hasJustMarried() {
        EventInstanceManager eim = owner.getEventInstance();
        if (eim != null) {
            String prop = eim.getProperty("groomId");

            if (prop != null) {
                return (Integer.parseInt(prop) == owner.getId() || eim.getIntProperty("brideId") == owner.getId()) &&
                        (owner.getMapId() == MapId.CHAPEL_WEDDING_ALTAR || owner.getMapId() == MapId.CATHEDRAL_WEDDING_ALTAR);
            }
        }

        return false;
    }

    Marriage getMarriageInstance() {
        return (Marriage) owner.getEventInstance();
    }

    // ── 变更 ──

    void addPlayerRing(Ring ring) {
        int ringItemId = ring.getItemId();
        if (ItemId.isWeddingRing(ringItemId)) {
            this.marriageRing = ring;
        } else if (ring.getItemId() > 1112012) {
            synchronized (friendshipRings) {
                this.friendshipRings.add(ring);
            }
        } else {
            synchronized (crushRings) {
                this.crushRings.add(ring);
            }
        }
    }

    /** 通知婚姻伴侣角色换图（CharacterMap 换图流程调用） */
    void notifyMapTransferToPartner(int mapid) {
        if (partnerId > 0) {
            final Character partner = owner.getWorldServer().getPlayerStorage().getCharacterById(partnerId);
            if (partner != null && !partner.isAwayFromWorld()) {
                partner.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(owner.getId(), mapid));
            }
        }
    }
}
