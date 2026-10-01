package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * COOLDOWN 封包树：单技能冷却显示。
 * time 当前语义面恒 0（到期/重置）；非零预留。
 */
public record CooldownPacket(int skillId, short time) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.COOLDOWN;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeIntLE(skillId);
        out.writeShortLE(time);
        return out;
    }
}
