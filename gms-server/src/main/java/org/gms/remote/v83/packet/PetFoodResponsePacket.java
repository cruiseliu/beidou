package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * PET_COMMAND（0xAE，response=1）：喂食反馈（enjoyed + 气球佩戴位）。
 */
public record PetFoodResponsePacket(int cid, byte index, boolean success, boolean hasChatBalloon) {
    private static final int OPCODE = 0xAE;

    public static ByteBuf encode(PetFoodResponsePacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeIntLE(packet.cid());
        out.writeByte(packet.index());
        out.writeByte(1);
        out.writeBoolean(packet.success());
        out.writeBoolean(packet.hasChatBalloon());
        return out;
    }
}
