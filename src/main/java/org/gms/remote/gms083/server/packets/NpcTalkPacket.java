package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

/**
 * NPC 对话页（0x130）：byte 4 + int npc + byte msgType + byte speaker +
 * writeString(talk) + endBytes。经 {@link OutPacket}（canonical builder）编码，
 * 与 PacketCreator.getNPCTalk 逐字节一致；writeString 的字符集走
 * ThreadLocalUtil（strand 任务已播种本 client）。
 */
public record NpcTalkPacket(int npc, int msgType, int speaker, String talk, int[] endBytes)
        implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.NPC_TALK;
    }

    @Override
    public ByteBuf encode() {
        OutPacket p = OutPacket.create(SendOpcode.NPC_TALK);
        p.writeByte(4); // ?
        p.writeInt(npc);
        p.writeByte(msgType);
        p.writeByte(speaker);
        p.writeString(talk);
        for (int b : endBytes) {
            p.writeByte(b);
        }
        return Unpooled.wrappedBuffer(p.getBytes());
    }
}
