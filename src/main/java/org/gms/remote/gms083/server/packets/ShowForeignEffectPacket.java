package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * SHOW_FOREIGN_EFFECT：他人视角演出帧，按形态分体：
 * <ul>
 *   <li>{@link Body.PetLevelUp}：宠物升级演出（batch 4 + 保留位 + 槽位）。</li>
 *   <li>{@link Body.Effect}：单字节效果码（9 = 任务完成，7 = 进门音效，8 = 转职…）。</li>
 * </ul>
 */
public record ShowForeignEffectPacket(Body body) implements V83Packet {

    public sealed interface Body {
        record PetLevelUp(int cid, byte petIndex) implements Body {
        }

        record Effect(int cid, byte effect) implements Body {
        }
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_FOREIGN_EFFECT;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        switch (body) {
            case Body.PetLevelUp(int cid, byte petIndex) -> {
                out.writeIntLE(cid);
                out.writeByte(4);
                out.writeByte(0);
                out.writeByte(petIndex);
            }
            case Body.Effect(int cid, byte effect) -> {
                out.writeIntLE(cid);
                out.writeByte(effect);
            }
        }
        return out;
    }
}
