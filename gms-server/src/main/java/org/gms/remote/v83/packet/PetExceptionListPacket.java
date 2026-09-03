package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;

/**
 * PET_EXCEPTION_LIST（0xAD）：拾取过滤列表下发。
 */
public record PetExceptionListPacket(int cid, byte petIndex, long petId, List<Integer> itemIds) {
    private static final int OPCODE = 0xAD;

    public static ByteBuf encode(PetExceptionListPacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeIntLE(packet.cid());
        out.writeByte(packet.petIndex());
        out.writeLongLE(packet.petId());
        out.writeByte(packet.itemIds().size());
        for (int id : packet.itemIds()) {
            out.writeIntLE(id);
        }
        return out;
    }
}
