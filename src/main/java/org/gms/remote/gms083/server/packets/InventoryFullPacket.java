package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * SHOW_STATUS_INFO/mode 0xff：背包满提示（整包常量）。
 */
public record InventoryFullPacket() implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_STATUS_INFO;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeByte(0);
        out.writeByte(0xff);
        out.writeIntLE(0);
        return out;
    }
}
