package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.server.translators.Filetimes;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * SHOW_STATUS_INFO（opcode 对齐 record，语义分体 = sealed body）：
 * <ul>
 *   <li>{@link Body.QuestStatus}：任务状态/进度更新（progressData = 逐 mob 三位计数拼接串；
 *       尾随 5 字节保留位）。</li>
 *   <li>{@link Body.Forfeit}：任务放弃（状态位恒 0，无进度段）。</li>
 *   <li>{@link Body.Completed}：任务完成（wire 时间 = completionTime 的文件时间换算）。</li>
 *   <li>{@link Body.InventoryFull}：背包满提示（mode 0xff，整包常量）。</li>
 *   <li>{@link Body.ExpGain}：经验获得（type 3；white/gain/inChat 为显示事实，奖励段
 *       恒 0——party/equip/cafe/rainbow 奖励行未接入语义层，接入时增字段）。</li>
 * </ul>
 */
public record ShowStatusInfoPacket(Body body) implements V83Packet {

    public sealed interface Body {
        record QuestStatus(int questId, int status, String progressData) implements Body {
        }

        record Forfeit(int questId) implements Body {
        }

        record Completed(int questId, long completionTime) implements Body {
        }

        record InventoryFull() implements Body {
        }

        record ExpGain(boolean white, int gain, boolean inChat) implements Body {
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
        switch (body) {
            case Body.QuestStatus(int questId, int status, String progressData) -> {
                p.writeByte(1);
                p.writeShort(questId);
                p.writeByte(status);
                p.writeString(progressData);
                p.skip(5);
            }
            case Body.Forfeit(int questId) -> {
                p.writeByte(1);
                p.writeShort(questId);
                p.writeByte(0);
            }
            case Body.Completed(int questId, long completionTime) -> {
                p.writeByte(1);
                p.writeShort(questId);
                p.writeByte(2);
                p.writeLong(Filetimes.toWire(completionTime));
            }
            case Body.InventoryFull() -> {
                p.writeByte(0);
                p.writeByte(0xff);
                p.writeInt(0);
            }
            case Body.ExpGain(boolean white, int gain, boolean inChat) -> {
                p.writeByte(3);          // 3 = exp, 4 = fame, 5 = mesos, 6 = guildpoints
                p.writeBool(white);
                p.writeInt(gain);
                p.writeBool(inChat);
                p.writeInt(0);           // bonus event exp
                p.writeByte(0);          // third monster kill event
                p.writeByte(0);          // RIP byte, this is always a 0
                p.writeInt(0);           // wedding bonus
                if (inChat) {            // quest bonus rate stuff
                    p.writeByte(0);
                }
                p.writeByte(0);          // 0 = party bonus, 100 = 1x Bonus EXP, 200 = 2x Bonus EXP
                p.writeInt(0);           // party bonus
                p.writeInt(0);           // equip bonus
                p.writeInt(0);           // Internet Cafe Bonus
                p.writeInt(0);           // Rainbow Week Bonus
            }
        }
        return p.build();
    }
}
