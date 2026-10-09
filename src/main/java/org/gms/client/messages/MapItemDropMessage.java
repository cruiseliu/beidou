package org.gms.client.messages;

import org.gms.infra.ActorMessage;
import org.gms.server.maps.MapItem;

import java.awt.Point;

/**
 * map → player：视野内掉落物落地（值快照，原 spawnDrop bakery 直发段的值化）。
 * 载荷 = {@link MapItem} 构造时点的全部包构值（无活引用），投递到视野内各 viewer 的
 * strand，由 viewer 域完成 needQuestItem 过滤（规则与 legacy 一致）与构包直发——
 * map 侧不再逐 viewer 活读/ref 守卫读。mapId 供接收方校验自身所在图（切图竞态丢弃）。
 */
public record MapItemDropMessage(
        int mapId, int oid, int itemId, int meso, int questid,
        int characterOwnerId, int partyOwnerId, long dropTime,
        long itemExpiration,
        byte dropType, boolean playerDrop, int dropperOid,
        Point dropfrom, Point dropto, byte mod) implements ActorMessage {

    /** spawn 时点值冻结（Point 拷贝防改动；itemExpiration 对 meso 包无意义恒 0） */
    public static MapItemDropMessage of(int mapId, MapItem drop, int dropperOid, Point dropfrom, Point dropto, byte mod) {
        return new MapItemDropMessage(
                mapId,
                drop.getObjectId(),
                drop.getItemId(),
                drop.getMeso(),
                drop.getQuest(),
                drop.getCharacterOwnerId(),
                drop.getPartyOwnerId(),
                drop.getDropTime(),
                drop.getItemExpiration(),
                drop.getDropType(),
                drop.isPlayerDrop(),
                dropperOid,
                new Point(dropfrom),
                new Point(dropto),
                mod);
    }
}
