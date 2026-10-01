package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

/**
 * 过场 UI 图（0xCE/item-inchat 帧）：byte 0x17 + writeString(path) + int 1。
 * 经 OutPacket（canonical builder）编码，与 PacketCreator.showInfo 逐字节一致。
 */
public record ShowInfoPacket(String path) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_ITEM_GAIN_INCHAT;
    }

    @Override
    public ByteBuf encode() {
        OutPacket p = OutPacket.create(SendOpcode.SHOW_ITEM_GAIN_INCHAT);
        p.writeByte(0x17);
        p.writeString(path);
        p.writeInt(1);
        return Unpooled.wrappedBuffer(p.getBytes());
    }
}
