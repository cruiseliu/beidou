package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * SHOW_STATUS_INFO(0x27)/mode 0xff：背包满提示（整包常量）。
 */
public record InventoryFullPacket() {
    private static final int OPCODE = 0x27;

    public static ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeByte(0);
        out.writeByte(0xff);
        out.writeIntLE(0);
        return out;
    }
}
