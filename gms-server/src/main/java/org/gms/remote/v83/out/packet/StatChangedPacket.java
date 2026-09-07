package org.gms.remote.v83.out.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

import java.util.ArrayList;
import java.util.List;

/**
 * STAT_CHANGED 封包树，按 opcode 一 record，形态为嵌套 Body：
 * <ul>
 *   <li>{@link Body.Stats}：mask 升序的属性条目 + SP 表职业分桶块（变长，占 0x8000 位）。
 *       mask 为派生值（工厂计算）；历史宽度表原样保留（0x1→byte / ≤0x4→int / <0x20→byte /
 *       <0xFFFF→short / 0x20000→short / 其余→int）——勿"修正"。</li>
 *   <li>{@link Body.PetIds}：仅 PET 掩码位（0x180008），三召唤槽 petid 长整型 + 终止位。
 *       客户端语义：槽位 -> petid 指派刷新（面板数值本体走宠物物品体，不经本包）。</li>
 * </ul>
 */
public record StatChangedPacket(boolean unlockActions, Body body) implements V83Packet {
    private static final int SP_TABLE_MASK = 0x8000;
    private static final int PET_MASK = 0x180008;

    public sealed interface Body {
        record Stats(int mask, List<StatEntry> entries, SpBuckets spBuckets) implements Body {
        }

        record PetIds(long[] petIds) implements Body {
        }
    }

    /** 属性条目形态（mask 升序 + SP 桶） */
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
        return new StatChangedPacket(unlockActions, new Body.Stats(mask, List.copyOf(sorted), spBuckets));
    }

    /** 宠物槽位指派形态 */
    public static StatChangedPacket petIds(boolean unlockActions, long[] petIds) {
        return new StatChangedPacket(unlockActions, new Body.PetIds(petIds));
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.STAT_CHANGED;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeBoolean(unlockActions);
        switch (body) {
            case Body.Stats stats -> {
                out.writeIntLE(stats.mask());
                for (StatEntry e : stats.entries()) {
                    writeStatValue(out, e.mask(), e.value());
                }
                if (stats.spBuckets() != null) {
                    out.writeByte(stats.spBuckets().buckets().size());
                    for (SpBuckets.Bucket b : stats.spBuckets().buckets()) {
                        out.writeByte(b.index());
                        out.writeByte(b.sp());
                    }
                }
            }
            case Body.PetIds petIds -> {
                out.writeIntLE(PET_MASK);
                for (long petId : petIds.petIds()) {
                    out.writeLongLE(petId);
                }
                out.writeByte(0);
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
