package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

/**
 * SHOW_ITEM_GAIN_INCHAT：本人演出帧，按形态分体：
 * <ul>
 *   <li>{@link Body.PetLevelUp}：宠物升级演出（batch 4 + 保留位 + 槽位）。</li>
 *   <li>{@link Body.Effect}：单字节效果码（9 = 任务完成，7 = 进门音效，15 = 装备升级…）。</li>
 * </ul>
 */
public record ShowItemGainInchatPacket(Body body) implements V83Packet {

    public sealed interface Body {
        record PetLevelUp(byte petIndex) implements Body {
        }

        record Effect(byte effect) implements Body {
        }
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_ITEM_GAIN_INCHAT;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        switch (body) {
            case Body.PetLevelUp(byte petIndex) -> {
                out.writeByte(4);
                out.writeByte(0);
                out.writeByte(petIndex);
            }
            case Body.Effect(byte effect) -> out.writeByte(effect);
        }
        return out;
    }
}
