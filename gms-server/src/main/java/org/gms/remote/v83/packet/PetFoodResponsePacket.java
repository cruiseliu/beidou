package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * PET_COMMAND（response=1）：喂食反馈（enjoyed + 气球佩戴位）。
 */
public record PetFoodResponsePacket(int cid, byte index, boolean success, boolean hasChatBalloon) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.PET_COMMAND;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeIntLE(cid);
        out.writeByte(index);
        out.writeByte(1);
        out.writeBoolean(success);
        out.writeBoolean(hasChatBalloon);
        return out;
    }
}
