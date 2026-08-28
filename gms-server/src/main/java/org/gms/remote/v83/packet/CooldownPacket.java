package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * COOLDOWN（0xEA）封包树：单技能冷却显示。
 * time 当前语义面恒 0（到期/重置）；非零预留。
 */
public record CooldownPacket(int skillId, short time) {
    private static final int OPCODE = 0xEA;

    public static ByteBuf encode(CooldownPacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeIntLE(packet.skillId());
        out.writeShortLE(packet.time());
        return out;
    }
}
