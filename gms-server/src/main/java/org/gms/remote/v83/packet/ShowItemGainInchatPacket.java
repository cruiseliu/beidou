package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * SHOW_ITEM_GAIN_INCHAT（0xCE）：本人演出帧。当前 remote 层仅产出 batch 4
 * （宠物升级演出，发往本人）。
 */
public record ShowItemGainInchatPacket(byte petIndex) {
    private static final int OPCODE = 0xCE;

    public static ByteBuf encode(ShowItemGainInchatPacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeByte(4);
        out.writeByte(0);
        out.writeByte(packet.petIndex());
        return out;
    }
}
