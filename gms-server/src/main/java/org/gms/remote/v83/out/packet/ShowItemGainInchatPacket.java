package org.gms.remote.v83.out.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * SHOW_ITEM_GAIN_INCHAT：本人演出帧。当前 remote 层仅产出 batch 4
 * （宠物升级演出，发往本人）。
 */
public record ShowItemGainInchatPacket(byte petIndex) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_ITEM_GAIN_INCHAT;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeByte(4);
        out.writeByte(0);
        out.writeByte(petIndex);
        return out;
    }
}
