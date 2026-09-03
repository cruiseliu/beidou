package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * PET_NAMECHANGE（0xAC）：改名（携带名字标签佩戴位）。名字以会话编码字节传入。
 */
public record PetNameChangePacket(int cid, byte slot, byte[] name, boolean hasNameTag) {
    private static final int OPCODE = 0xAC;

    public static ByteBuf encode(PetNameChangePacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeIntLE(packet.cid());
        out.writeByte(packet.slot());
        out.writeShortLE(packet.name().length);
        out.writeBytes(packet.name());
        out.writeBoolean(packet.hasNameTag());
        return out;
    }
}
