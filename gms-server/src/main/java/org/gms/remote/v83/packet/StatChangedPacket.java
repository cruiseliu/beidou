package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * STAT_CHANGED（0x1F）封包树：mask 升序的属性条目 + SP 表职业分桶块（变长，占 0x8000 位）。
 * mask 为派生值（工厂计算）；历史宽度表原样保留（0x1→byte / ≤0x4→int / <0x20→byte /
 * <0xFFFF→short / 0x20000→short / 其余→int）——勿"修正"。
 */
public record StatChangedPacket(boolean unlockActions, int mask,
                                List<StatEntry> entries, SpBuckets spBuckets) {
    private static final int OPCODE = 0x1F;
    private static final int SP_TABLE_MASK = 0x8000;

    public static StatChangedPacket of(boolean unlockActions, List<StatEntry> entries,
                                       SpBuckets spBuckets) {
        List<StatEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Integer.compare(a.mask(), b.mask()));
        int mask = 0;
        for (StatEntry e : sorted) {
            mask |= e.mask();
        }
        if (spBuckets != null) {
            mask |= SP_TABLE_MASK;
        }
        return new StatChangedPacket(unlockActions, mask, List.copyOf(sorted), spBuckets);
    }

    public static ByteBuf encode(StatChangedPacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeBoolean(packet.unlockActions());
        out.writeIntLE(packet.mask());
        for (StatEntry e : packet.entries()) {
            writeStatValue(out, e.mask(), e.value());
        }
        if (packet.spBuckets() != null) {
            out.writeByte(packet.spBuckets().buckets().size());
            for (SpBuckets.Bucket b : packet.spBuckets().buckets()) {
                out.writeByte(b.index());
                out.writeByte(b.sp());
            }
        }
        return out;
    }

    private static void writeStatValue(ByteBuf out, int mask, int value) {
        if (mask == 0x1) {
            out.writeByte(value);
        } else if (mask <= 0x4) {
            out.writeIntLE(value);
        } else if (mask < 0x20) {
            out.writeByte(value);
        } else if (mask < 0xFFFF) {
            out.writeShortLE(value);
        } else if (mask == 0x20000) {
            out.writeShortLE(value);
        } else {
            out.writeIntLE(value);
        }
    }

    public record StatEntry(int mask, int value) {
    }

    public record SpBuckets(List<Bucket> buckets) {
        public static SpBuckets of(int[] remainingSp) {
            List<Bucket> buckets = new ArrayList<>();
            for (int i = 0; i < remainingSp.length; i++) {
                if (remainingSp[i] > 0) {
                    buckets.add(new Bucket(i + 1, remainingSp[i]));
                }
            }
            return new SpBuckets(List.copyOf(buckets));
        }

        public record Bucket(int index, int sp) {
        }
    }
}
