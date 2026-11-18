package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.client.packets.NpcActionClientPacket;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * NPC_ACTION（S→C）：客户端动画状态机 loopback 帧（doc/13 传输回声——服务端零语义消费，
 * 按收包解码载荷原样回放；发送统一走版本 send 路径，本类只编码）。
 */
public record NpcActionServerPacket(
    NpcActionClientPacket.Action source
) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.NPC_ACTION;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder p = new ByteBufBuilder();
        p.writeShort(opcode().getValue());
        switch (source) {
            case NpcActionClientPacket.Talk talk -> {
                p.writeInt(talk.oid());
                p.writeByte(talk.action());
                p.writeByte(talk.frame());                
            }

            case NpcActionClientPacket.Move move -> {
                p.writeInt(move.oid());
                p.writeByte(move.action());
                p.writeByte(move.frame());                
                p.writeShort(move.x());
                p.writeShort(move.y());
                move.move().encode(p);
            }
        }

        return p.build();

        // ByteBuf out = Unpooled.buffer();
        // out.writeShortLE(opcode().getValue());
        // out.writeBytes(data);
        // return out;
    }
}
