package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.constants.string.CharsetConstants;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * SPAWN_MONSTER_CONTROL 的 stop 形态（0x00 头）：writeByte(0) + writeInt(oid)，
 * 与历史 PacketCreator.stopControllingMonster 逐字节一致。授控全身形态（0x01/0x02）
 * 归 {@link ControlMonsterPacket}。
 */
public record StopControlMonsterPacket(int oid) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SPAWN_MONSTER_CONTROL;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder out = new ByteBufBuilder(CharsetConstants.getCharset(0));
        out.writeShort(opcode().getValue());
        out.writeByte(0);
        out.writeInt(oid);
        return Unpooled.wrappedBuffer(out.getBytes());
    }
}
