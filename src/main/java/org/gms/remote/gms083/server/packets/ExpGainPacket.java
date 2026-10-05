package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * SHOW_STATUS_INFO 的经验获得帧（type 3；与任务状态帧/背包满帧共 opcode，语义分体）。
 * white/gain/inChat 为显示事实；party/equip/cafe/rainbow 奖励段恒 0——奖励行未接入
 * 语义层，接入时增字段。
 */
public record ExpGainPacket(boolean white, int gain, boolean inChat) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SHOW_STATUS_INFO;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder p = new ByteBufBuilder();
        p.writeShort(opcode().getValue());
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
        return p.build();
    }
}
