package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.server.translators.Filetimes;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * SHOW_STATUS_INFO 的任务状态帧（quest 体，按 opcode 一 record、嵌套 sealed body）：
 * <ul>
 *   <li>{@link Body.Update}：状态/进度更新（status = QuestStatus 枚举值 0/1/2，
 *       progressData = 逐 mob 三位计数的拼接串；尾随 5 字节保留位）。</li>
 *   <li>{@link Body.Forfeit}：放弃（状态位恒 0，无进度段）。</li>
 *   <li>{@link Body.Completed}：完成（wire 时间 = completionTime 的文件时间换算，
 *       与 PacketCreator.getTime 同式）。</li>
 * </ul>
 */
public record QuestStatusPacket(Body body) implements V83Packet {

    public sealed interface Body {
        record Update(int questId, int status, String progressData) implements Body {
        }

        record Forfeit(int questId) implements Body {
        }

        record Completed(int questId, long completionTime) implements Body {
        }
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_STATUS_INFO;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder p = new ByteBufBuilder();
        p.writeShort(opcode().getValue());
        p.writeByte(1);
        switch (body) {
            case Body.Update(int questId, int status, String progressData) -> {
                p.writeShort(questId);
                p.writeByte(status);
                p.writeString(progressData);
                p.skip(5);
            }
            case Body.Forfeit(int questId) -> {
                p.writeShort(questId);
                p.writeByte(0);
            }
            case Body.Completed(int questId, long completionTime) -> {
                p.writeShort(questId);
                p.writeByte(2);
                p.writeLong(Filetimes.toWire(completionTime));
            }
        }
        return p.build();
    }
}
