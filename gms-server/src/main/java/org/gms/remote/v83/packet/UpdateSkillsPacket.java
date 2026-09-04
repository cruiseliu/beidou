package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

import java.util.List;

/**
 * UPDATE_SKILLS 封包树：技能学习/更新/移除的 count 列表。
 * 移除 = level -1 条目；expiration 为 v83 文件时间值（translate 层已换算）。
 */
public record UpdateSkillsPacket(List<SkillEntry> entries) implements V83Packet {
    /** 防御拷贝：encode 已上移至 route 层，record 不得持有 translate 层后续会清空的活列表 */
    public UpdateSkillsPacket {
        entries = List.copyOf(entries);
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.UPDATE_SKILLS;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeByte(1);   // phase 常量：学习/更新
        out.writeShortLE(entries.size());
        for (SkillEntry e : entries) {
            out.writeIntLE(e.skillId());
            out.writeIntLE(e.level());
            out.writeIntLE(e.masterLevel());
            out.writeLongLE(e.expiration());
        }
        out.writeByte(4);   // 尾常量：结束标记
        return out;
    }

    public record SkillEntry(int skillId, int level, int masterLevel, long expiration) {
    }
}
