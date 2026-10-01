package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

/**
 * SERVERMESSAGE（serverNotice 的 self 通知形态）：byte type + writeString(message)
 * + 类型条件尾（3=megaphone 通道/耳朵、6=lightblue int 0、7=npc int 0）。
 * 经 OutPacket（canonical builder）编码，与 PacketCreator.serverNotice(type, message)
 * 逐字节一致。
 */
public record ServerMessagePacket(int type, String message) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SERVERMESSAGE;
    }

    @Override
    public ByteBuf encode() {
        OutPacket p = OutPacket.create(SendOpcode.SERVERMESSAGE);
        p.writeByte(type);
        p.writeString(message);
        if (type == 3) {
            p.writeByte(-1);   // channel - 1（serverNotice 固定 channel=0）
            p.writeBool(false);
        } else if (type == 6) {
            p.writeInt(0);
        } else if (type == 7) {
            p.writeInt(0);
        }
        return Unpooled.wrappedBuffer(p.getBytes());
    }
}
