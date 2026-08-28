package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;

/**
 * UPDATE_SKILLS（0x24）封包树：技能学习/更新/移除的 count 列表。
 * 移除 = level -1 条目；expiration 为 v83 文件时间值（translate 层已换算）。
 */
public record UpdateSkillsPacket(List<SkillEntry> entries) {
    private static final int OPCODE = 0x24;

    public static ByteBuf encode(UpdateSkillsPacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeByte(1);   // phase 常量：学习/更新
        out.writeShortLE(packet.entries().size());
        for (SkillEntry e : packet.entries()) {
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
