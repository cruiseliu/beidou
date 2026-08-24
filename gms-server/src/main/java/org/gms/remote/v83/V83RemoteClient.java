package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.client.JobEnum;
import org.gms.client.character.Stat;
import org.gms.constants.game.GameConstants;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;
import org.gms.remote.RemoteClient;
import org.gms.remote.RemoteUpdate;
import org.gms.remote.SpUpdate;
import org.gms.remote.StatsUpdate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * RemoteClient 的 v83 实现。当前支持 updateStats / updateSp / unlockActions
 * （合并为 STAT_CHANGED），编码逻辑自 PacketCreator.updatePlayerStats 逐字节移植
 * （宽度分派、mask 升序）。面板属性 + hp/mp/ap + SP 合并编码；P_ATK/M_ATK 无 wire 位，丢弃。
 */
public final class V83RemoteClient implements RemoteClient {
    private final Client client;
    private boolean txOpen = false;

    public V83RemoteClient(Client client) {
        this.client = client;
    }

    @Override
    public synchronized RemoteUpdate update() {
        if (txOpen) {
            throw new IllegalStateException("上一个 RemoteUpdate 尚未 commit");
        }
        txOpen = true;
        return new Tx();
    }

    private final class Tx implements RemoteUpdate {
        private final EnumMap<Stat, Integer> panel = new EnumMap<>(Stat.class);
        private Integer hp;
        private Integer mp;
        private Integer ap;
        private SpUpdate sp;
        private boolean unlockActions = false;
        private boolean finished = false;

        @Override
        public RemoteUpdate updateStats(StatsUpdate update) {
            checkOpen();
            panel.putAll(update.panel());
            if (update.hp() != null) {
                hp = update.hp();
            }
            if (update.mp() != null) {
                mp = update.mp();
            }
            if (update.ap() != null) {
                ap = update.ap();
            }
            return this;
        }

        @Override
        public RemoteUpdate updateSp(SpUpdate update) {
            checkOpen();
            sp = update;
            return this;
        }

        @Override
        public RemoteUpdate unlockActions() {
            checkOpen();
            unlockActions = true;
            return this;
        }

        @Override
        public void commit() {
            flush();
        }

        @Override
        public void close() {
            flush();
        }

        private void flush() {
            if (finished) {
                return;
            }
            finished = true;
            synchronized (V83RemoteClient.this) {
                txOpen = false;
            }
            if (panel.isEmpty() && hp == null && mp == null && ap == null && sp == null && !unlockActions) {
                return;
            }
            client.sendPacket(encodeStatsChanged(this));
        }

        private void checkOpen() {
            if (finished) {
                throw new IllegalStateException("RemoteUpdate 已 commit，不可继续使用");
            }
        }
    }

    // ── v83 STAT_CHANGED 编码（自 PacketCreator.updatePlayerStats 移植）──

    /** 语义字段 → v83 STAT_CHANGED 的 mask 位；P_ATK/M_ATK 无 wire 位不入表（丢弃）。 */
    private static final EnumMap<Stat, Integer> WIRE_MASK = new EnumMap<>(Stat.class);
    private static final int MASK_HP = 0x400;
    private static final int MASK_MP = 0x1000;
    private static final int MASK_AVAILABLE_AP = 0x4000;
    private static final int MASK_AVAILABLE_SP = 0x8000;

    static {
        WIRE_MASK.put(Stat.STR, 0x40);
        WIRE_MASK.put(Stat.DEX, 0x80);
        WIRE_MASK.put(Stat.INT, 0x100);
        WIRE_MASK.put(Stat.LUK, 0x200);
        WIRE_MASK.put(Stat.MAX_HP, 0x800);
        WIRE_MASK.put(Stat.MAX_MP, 0x2000);
    }

    /** v83 SP 表职业（如龙神）的 SP 写分桶变长块而非单个 short。 */
    private static boolean spTableJob(int jobId) {
        JobEnum job = JobEnum.getById(jobId);
        return job != null && GameConstants.hasSPTable(job);
    }

    private record WireEntry(int mask, int value) {
    }

    private static Packet encodeStatsChanged(Tx tx) {
        boolean spTable = tx.sp != null && spTableJob(tx.sp.jobId());
        List<WireEntry> entries = new ArrayList<>(tx.panel.size() + 4);
        for (var e : tx.panel.entrySet()) {
            Integer mask = WIRE_MASK.get(e.getKey());
            if (mask != null) {  // P_ATK/M_ATK：语义层有、本版本 wire 无，丢弃
                entries.add(new WireEntry(mask, e.getValue()));
            }
        }
        if (tx.hp != null) {
            entries.add(new WireEntry(MASK_HP, tx.hp));
        }
        if (tx.mp != null) {
            entries.add(new WireEntry(MASK_MP, tx.mp));
        }
        if (tx.ap != null) {
            entries.add(new WireEntry(MASK_AVAILABLE_AP, tx.ap));
        }
        // SP 表职业的 SP 为变长块，占 0x8000 位但不走统一宽度分派
        if (tx.sp != null && !spTable) {
            entries.add(new WireEntry(MASK_AVAILABLE_SP, tx.sp.visibleSp()));
        }
        entries.sort((a, b) -> Integer.compare(a.mask(), b.mask()));

        OutPacket p = OutPacket.create(SendOpcode.STAT_CHANGED);
        p.writeBool(tx.unlockActions);
        int updateMask = 0;
        for (WireEntry e : entries) {
            updateMask |= e.mask();
        }
        if (spTable) {
            updateMask |= MASK_AVAILABLE_SP;
        }
        p.writeInt(updateMask);
        for (WireEntry e : entries) {
            int mask = e.mask();
            int value = e.value();
            if (mask == 0x1) {
                p.writeByte(value);
            } else if (mask <= 0x4) {
                p.writeInt(value);
            } else if (mask < 0x20) {
                p.writeByte((short) value);
            } else if (mask < 0xFFFF) {
                p.writeShort(value);
            } else if (mask == 0x20000) {
                p.writeShort(value);
            } else {
                p.writeInt(value);
            }
        }
        // SP 表职业的分桶块占 0x8000 位；当前语义面无 >0x8000 的 mask 条目，循环后追加
        // 位置天然正确（将来引入更高 mask 字段时改为在循环内按位插入）
        if (spTable) {
            writeSpBuckets(p, tx.sp.spByBucket());
        }
        return p;
    }

    /** SP 表职业的变长分桶块（自 PacketCreator.addRemainingSkillInfo 移植）。 */
    private static void writeSpBuckets(OutPacket p, int[] remainingSp) {
        int effectiveLength = 0;
        for (int j : remainingSp) {
            if (j > 0) {
                effectiveLength++;
            }
        }
        p.writeByte(effectiveLength);
        for (int i = 0; i < remainingSp.length; i++) {
            if (remainingSp[i] > 0) {
                p.writeByte(i + 1);
                p.writeByte(remainingSp[i]);
            }
        }
    }
}
