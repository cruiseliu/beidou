package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.remote.gms083.server.packets.V83Packet;

/**
 * PLAYER_HINT（教学提示 balloon）：writeString(message) + short width + short height + byte 1。
 * 尺寸归一与 PacketCreator.sendHint 逐字节一致：width &lt; 1 → max(len*10, 40)；height &lt; 5 → 5。
 */
public record PlayerHintPacket(String message, int width, int height) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.PLAYER_HINT;
    }

    @Override
    public ByteBuf encode() {
        int w = width < 1 ? Math.max(message.length() * 10, 40) : width;
        int h = Math.max(height, 5);
        OutPacket p = OutPacket.create(SendOpcode.PLAYER_HINT);
        p.writeString(message);
        p.writeShort(w);
        p.writeShort(h);
        p.writeByte(1);
        return Unpooled.wrappedBuffer(p.getBytes());
    }
}
