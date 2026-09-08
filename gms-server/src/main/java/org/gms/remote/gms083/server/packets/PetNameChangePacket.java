package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * PET_NAMECHANGE：改名（携带名字标签佩戴位）。名字以会话编码字节传入。
 */
public record PetNameChangePacket(int cid, byte slot, byte[] name, boolean hasNameTag) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.PET_NAMECHANGE;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeIntLE(cid);
        out.writeByte(slot);
        out.writeShortLE(name.length);
        out.writeBytes(name);
        out.writeBoolean(hasNameTag);
        return out;
    }
}
