package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * SHOW_FOREIGN_EFFECT（0xC6）：他人视角演出帧。当前 remote 层仅产出 batch 4
 * （宠物升级演出，全图广播）。
 */
public record ShowForeignEffectPacket(int cid, byte petIndex) {
    private static final int OPCODE = 0xC6;

    public static ByteBuf encode(ShowForeignEffectPacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeIntLE(packet.cid());
        out.writeByte(4);
        out.writeByte(0);
        out.writeByte(packet.petIndex());
        return out;
    }
}
