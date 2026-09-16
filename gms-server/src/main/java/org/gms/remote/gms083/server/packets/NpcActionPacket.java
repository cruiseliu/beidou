package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * NPC_ACTION（S→C）：客户端动画状态机 loopback 帧（doc/13 传输回声——服务端零语义消费，
 * 按收包解码载荷原样回放；发送统一走版本 send 路径，本类只编码）。
 */
public record NpcActionPacket(byte[] data) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.NPC_ACTION;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeBytes(data);
        return out;
    }
}
