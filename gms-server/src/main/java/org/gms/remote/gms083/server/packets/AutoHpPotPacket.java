package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * AUTO_HP_POT / AUTO_MP_POT（自动用药键位绑定，v83）：单一 itemId int（LE），0 = 未绑定。
 * 两个 opcode 同形，各一 record（每种 opcode 一个 record，见 package-client.md §7）。
 */
public record AutoHpPotPacket(int itemId) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.AUTO_HP_POT;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder out = new ByteBufBuilder();
        out.writeShort((short) opcode().getValue());
        out.writeInt(itemId());
        return out.build();
    }
}
