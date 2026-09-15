package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.utils.ByteBufBuilder;

import java.util.List;

/**
 * KEYMAP（键盘映射，v83）：mode 字节 + 固定 90 槽（type byte + action int，LE）。
 * 槽位键码不入 wire（隐含索引）；空槽 = type 0 / action 0（归一在 translator 完成）。
 */
public record KeymapPacket(List<Binding> bindings) implements V83Packet {

    /** 槽位总数：wire 常量（历史宽度表，0..89 隐含索引） */
    public static final int SLOT_COUNT = 90;

    public KeymapPacket {
        if (bindings.size() != SLOT_COUNT) {
            throw new IllegalArgumentException("键位表必须为 " + SLOT_COUNT + " 槽: " + bindings.size());
        }
    }

    /** 单槽绑定：type = 绑定类别，action = 动作 id（skill/item/...） */
    public record Binding(byte type, int action) {
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.KEYMAP;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder out = new ByteBufBuilder();
        out.writeShort((short) opcode().getValue());
        out.writeByte(0);   // mode（wire 常量）
        for (Binding b : bindings) {
            out.writeByte(b.type());
            out.writeInt(b.action());
        }
        return out.build();
    }
}
