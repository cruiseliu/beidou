package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * QUICKSLOT_INIT（快捷栏初始化，v83）：custom 布尔 + 自定义时 8 × key int（LE）。
 * 布尔 = 有自定义绑定（false = 与客户端默认表相同，客户端跳过解析走
 * CQuickslotKeyMappedMan 默认键位）；判定在 translator 固化。
 */
public record QuickslotPacket(boolean custom, byte[] keys) implements V83Packet {

    public static final int SLOT_COUNT = 8;

    public QuickslotPacket {
        if (keys.length != SLOT_COUNT) {
            throw new IllegalArgumentException("快捷栏必须为 " + SLOT_COUNT + " 槽: " + keys.length);
        }
        keys = keys.clone();
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.QUICKSLOT_INIT;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder out = new ByteBufBuilder();
        out.writeShort((short) opcode().getValue());
        out.writeBool(custom());
        if (custom()) {
            for (byte key : keys()) {
                // Nexon 以 int 发送（CFuncKeyMapped::m_aQuickslotKeyMapped 为 int[8]），
                // 写 byte 会 38 错误崩溃
                out.writeInt(key);
            }
        }
        return out.build();
    }
}
