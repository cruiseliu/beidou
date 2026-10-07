package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

/**
 * SHOW_MONSTER_HP：writeInt(oid) + writeByte(remainingHpPercent)，与历史
 * PacketCreator.showMonsterHP 逐字节一致。
 */
public record ShowMonsterHpPacket(int oid, int hpPercent) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_MONSTER_HP;
    }

    @Override
    public ByteBuf encode() {
        OutPacket p = OutPacket.create(SendOpcode.SHOW_MONSTER_HP);
        p.writeInt(oid);
        p.writeByte(hpPercent);
        return Unpooled.wrappedBuffer(p.getBytes());
    }
}
