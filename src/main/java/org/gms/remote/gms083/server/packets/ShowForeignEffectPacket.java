package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * SHOW_FOREIGN_EFFECT：他人视角演出帧。当前 remote 层仅产出 batch 4
 * （宠物升级演出，全图广播）。
 */
public record ShowForeignEffectPacket(int cid, byte petIndex) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_FOREIGN_EFFECT;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeIntLE(cid);
        out.writeByte(4);
        out.writeByte(0);
        out.writeByte(petIndex);
        return out;
    }
}
