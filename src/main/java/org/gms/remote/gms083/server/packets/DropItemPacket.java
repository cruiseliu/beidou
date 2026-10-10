package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

import java.awt.Point;

/**
 * DROP_ITEM_FROM_MAPOBJECT：mod + oid + meso 标记 + itemId + owner 标识 + dropType +
 * 落点 + dropper oid（mod!=2 追加起点与占位 fh；非 meso 追加 wire 文件时间）+
 * 拾取标记，与历史 PacketCreator.dropItemFromMapObject 逐字节一致。
 * expiration 已在 route 层换算为 wire 文件时间。
 */
public record DropItemPacket(int oid, int itemId, int meso, int ownerId, byte dropType,
        boolean playerDrop, int dropperOid, long expirationWire,
        Point dropfrom, Point dropto, byte mod) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.DROP_ITEM_FROM_MAPOBJECT;
    }

    @Override
    public ByteBuf encode() {
        OutPacket p = OutPacket.create(SendOpcode.DROP_ITEM_FROM_MAPOBJECT);
        p.writeByte(mod);
        p.writeInt(oid);
        p.writeBool(meso > 0); // 1 mesos, 0 item, 2 and above all item meso bag,
        p.writeInt(itemId); // drop object ID
        p.writeInt(ownerId); // owner charid/partyid :)
        p.writeByte(dropType); // 0 = timeout for non-owner, 1 = timeout for non-owner's party, 2 = FFA, 3 = explosive/FFA
        p.writePos(dropto);
        p.writeInt(dropperOid); // dropper oid, found thanks to Li Jixue

        if (mod != 2) {
            p.writePos(dropfrom);
            p.writeShort(0);//Fh?
        }
        if (meso == 0) {
            p.writeLong(expirationWire);
        }
        p.writeByte(playerDrop ? 0 : 1); //pet EQP pickup
        return Unpooled.wrappedBuffer(p.getBytes());
    }
}
