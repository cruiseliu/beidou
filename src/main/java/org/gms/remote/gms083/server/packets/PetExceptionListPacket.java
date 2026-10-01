package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

import java.util.List;

/**
 * PET_EXCEPTION_LIST：拾取过滤列表下发。
 */
public record PetExceptionListPacket(int cid, byte petIndex, long petId, List<Integer> itemIds) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.PET_EXCEPTION_LIST;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeIntLE(cid);
        out.writeByte(petIndex);
        out.writeLongLE(petId);
        out.writeByte(itemIds.size());
        for (int id : itemIds) {
            out.writeIntLE(id);
        }
        return out;
    }
}
