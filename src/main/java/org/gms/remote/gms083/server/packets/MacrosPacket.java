package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.utils.ByteBufBuilder;

import java.util.List;

/**
 * MACRO_SYS_DATA_INIT（技能宏初始化/重推，v83）：count 字节 + 逐宏
 * （name 短前缀字符串 + shout byte + skill1..3 int×3，LE）。空位不写——
 * count 只计非空宏（归一在 translator 完成）。
 */
public record MacrosPacket(List<Macro> macros) implements V83Packet {

    /** 单宏：name（宏名）、shout（喊话键）、skill1..3（三技能位，0 = 空） */
    public record Macro(String name, byte shout, int skill1, int skill2, int skill3) {
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.MACRO_SYS_DATA_INIT;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder out = new ByteBufBuilder();
        out.writeShort((short) opcode().getValue());
        out.writeByte(macros().size());
        for (Macro m : macros()) {
            out.writeString(m.name());
            out.writeByte(m.shout());
            out.writeInt(m.skill1());
            out.writeInt(m.skill2());
            out.writeInt(m.skill3());
        }
        return out.build();
    }
}
