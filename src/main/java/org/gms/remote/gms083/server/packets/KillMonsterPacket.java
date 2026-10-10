package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

/**
 * KILL_MONSTER：writeInt(oid) + writeByte(animation) ×2，与历史
 * PacketCreator.killMonster 逐字节一致。
 */
public record KillMonsterPacket(int oid, int animation) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.KILL_MONSTER;
    }

    @Override
    public ByteBuf encode() {
        OutPacket p = OutPacket.create(SendOpcode.KILL_MONSTER);
        p.writeInt(oid);
        p.writeByte(animation);
        p.writeByte(animation);
        return Unpooled.wrappedBuffer(p.getBytes());
    }
}
