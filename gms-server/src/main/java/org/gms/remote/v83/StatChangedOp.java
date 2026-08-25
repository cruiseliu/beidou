package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.client.JobEnum;
import org.gms.client.character.Stat;
import org.gms.constants.game.GameConstants;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;
import org.gms.remote.BasicUpdate;
import org.gms.remote.SpUpdate;
import org.gms.remote.StatsUpdate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * STAT_CHANGED（0x1F）：stats（面板+hp/mp/ap）、sp、basic（level/jobId/exp）、unlockActions
 * 四个语义域合并编码为一个包（mask 升序、宽度分派，自 PacketCreator.updatePlayerStats 逐字节移植）。
 * 绝对值后写覆盖 = 净 diff；P_ATK/M_ATK 无 wire 位丢弃。
 */
final class StatChangedOp implements V83Op {
    private final EnumMap<Stat, Integer> panel = new EnumMap<>(Stat.class);
    private Integer hp;
    private Integer mp;
    private Integer ap;
    private SpUpdate sp;
    private Integer level;
    private Integer jobId;
    private Long exp;
    private boolean unlockActions = false;

    void mergeStats(StatsUpdate update) {
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
    }

    void mergeSp(SpUpdate update) {
        sp = update;
    }

    void mergeBasic(BasicUpdate update) {
        if (update.jobId() != null) {
            jobId = update.jobId();
        }
        if (update.level() != null) {
            level = update.level();
        }
        if (update.exp() != null) {
            exp = update.exp();
        }
    }

    void unlockActions() {
        unlockActions = true;
    }

    @Override
    public boolean isEmpty() {
        return panel.isEmpty() && hp == null && mp == null && ap == null && sp == null
                && level == null && jobId == null && exp == null && !unlockActions;
    }

    @Override
    public void sendTo(Client client) {
        if (isEmpty()) {
            return;
        }
        client.sendPacket(encode());
        panel.clear();
        hp = mp = ap = null;
        sp = null;
        level = jobId = null;
        exp = null;
        unlockActions = false;
    }

    // ── 编码（自 PacketCreator.updatePlayerStats 移植）──

    /** 语义字段 → v83 STAT_CHANGED 的 mask 位；P_ATK/M_ATK 无 wire 位不入表（丢弃）。 */
    private static final EnumMap<Stat, Integer> WIRE_MASK = new EnumMap<>(Stat.class);
    private static final int MASK_LEVEL = 0x10;
    private static final int MASK_JOB = 0x20;
    private static final int MASK_HP = 0x400;
    private static final int MASK_MP = 0x1000;
    private static final int MASK_AVAILABLE_AP = 0x4000;
    private static final int MASK_AVAILABLE_SP = 0x8000;
    private static final int MASK_EXP = 0x10000;

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

    /** v83 客户端显示值：新手职业显示新手桶单值，其余显示非新手桶之和（旧版双槽显示语义）。 */
    private static int visibleSp(SpUpdate sp) {
        JobEnum job = JobEnum.getById(sp.jobId());
        if (job != null && job.isBeginnerJob()) {
            return sp.spByJob().getOrDefault(sp.jobId(), 0);
        }
        int sum = 0;
        for (var e : sp.spByJob().entrySet()) {
            JobEnum j = JobEnum.getById(e.getKey());
            if (j == null || !j.isBeginnerJob()) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    private record WireEntry(int mask, int value) {
    }

    private Packet encode() {
        boolean spTable = sp != null && spTableJob(sp.jobId());
        List<WireEntry> entries = new ArrayList<>(panel.size() + 6);
        if (level != null) {
            entries.add(new WireEntry(MASK_LEVEL, level));
        }
        if (jobId != null) {
            entries.add(new WireEntry(MASK_JOB, jobId));
        }
        for (var e : panel.entrySet()) {
            Integer mask = WIRE_MASK.get(e.getKey());
            if (mask != null) {  // P_ATK/M_ATK：语义层有、本版本 wire 无，丢弃
                entries.add(new WireEntry(mask, e.getValue()));
            }
        }
        if (hp != null) {
            entries.add(new WireEntry(MASK_HP, hp));
        }
        if (mp != null) {
            entries.add(new WireEntry(MASK_MP, mp));
        }
        if (ap != null) {
            entries.add(new WireEntry(MASK_AVAILABLE_AP, ap));
        }
        // SP 表职业的 SP 为变长块，占 0x8000 位但不走统一宽度分派
        if (sp != null && !spTable) {
            entries.add(new WireEntry(MASK_AVAILABLE_SP, visibleSp(sp)));
        }
        if (exp != null) {
            entries.add(new WireEntry(MASK_EXP, Math.toIntExact(exp)));
        }
        entries.sort((a, b) -> Integer.compare(a.mask(), b.mask()));

        OutPacket p = OutPacket.create(SendOpcode.STAT_CHANGED);
        p.writeBool(unlockActions);
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
            writeSpBuckets(p, sp.spByJob().values().stream().mapToInt(Integer::intValue).toArray());
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
