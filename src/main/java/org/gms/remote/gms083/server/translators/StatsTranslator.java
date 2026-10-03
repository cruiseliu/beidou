package org.gms.remote.gms083.server.translators;

import org.gms.client.JobEnum;
import org.gms.client.character.Stat;
import org.gms.constants.game.GameConstants;
import org.gms.remote.gms083.ServerTranslator;
import org.gms.remote.gms083.server.packets.StatChangedPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.modules.skills.server.SpUpdate;
import org.gms.remote.modules.stats.server.StatsUpdate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * stats 域翻译（含骑 STAT_CHANGED 便车的跨域字段）：面板/hp/mp/ap、level/jobId/exp、
 * SP（技能域语义）；unlockActions 首字节由本层自动置位（携带变更即解锁，显式事件仅剩纯解锁空包）。
 * last-wins 绝对值合并 = 净 diff。
 * 语义→wire 映射（多对多）：P_ATK/M_ATK 无 wire 位丢弃；SP 非表职业出单个 short、
 * 表职业出分桶变长块；新手职业显示新手桶单值，其余显示非新手桶之和。
 */
public final class StatsTranslator implements ServerTranslator {
    private static final int MASK_LEVEL = 0x10;
    private static final int MASK_JOB = 0x20;
    private static final int MASK_HP = 0x400;
    private static final int MASK_MP = 0x1000;
    private static final int MASK_AVAILABLE_AP = 0x4000;
    private static final int MASK_AVAILABLE_SP = 0x8000;
    private static final int MASK_EXP = 0x10000;

    /** 语义字段 → v83 mask 位；P_ATK/M_ATK 无 wire 位不入表（丢弃）。 */
    private static final EnumMap<Stat, Integer> WIRE_MASK = new EnumMap<>(Stat.class);

    static {
        WIRE_MASK.put(Stat.STR, 0x40);
        WIRE_MASK.put(Stat.DEX, 0x80);
        WIRE_MASK.put(Stat.INT, 0x100);
        WIRE_MASK.put(Stat.LUK, 0x200);
        WIRE_MASK.put(Stat.MAX_HP, 0x800);
        WIRE_MASK.put(Stat.MAX_MP, 0x2000);
    }

    private final EnumMap<Stat, Integer> panel = new EnumMap<>(Stat.class);
    private Integer hp;
    private Integer mp;
    private Integer ap;
    private SpUpdate sp;
    private Integer level;
    private Integer jobId;
    private Long exp;
    private boolean unlockActions = false;

    public void onStats(StatsUpdate update) {
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

    public void onSp(SpUpdate update) {
        sp = update;
    }

    public void onJob(int jobId) {
        this.jobId = jobId;
    }

    public void onLevel(int level) {
        this.level = level;
    }

    public void onExp(long exp) {
        this.exp = exp;
    }

    public void onUnlockActions() {
        unlockActions = true;
    }

    @Override
    public List<V83Packet> flush() {
        boolean spTable = sp != null && spTableJob(sp.jobId());
        List<StatChangedPacket.StatEntry> entries = new ArrayList<>(panel.size() + 6);
        if (level != null) {
            entries.add(new StatChangedPacket.StatEntry(MASK_LEVEL, level));
        }
        if (jobId != null) {
            entries.add(new StatChangedPacket.StatEntry(MASK_JOB, jobId));
        }
        for (var e : panel.entrySet()) {
            Integer mask = WIRE_MASK.get(e.getKey());
            if (mask != null) {   // P_ATK/M_ATK：语义层有、本版本 wire 无，丢弃
                entries.add(new StatChangedPacket.StatEntry(mask, e.getValue()));
            }
        }
        if (hp != null) {
            entries.add(new StatChangedPacket.StatEntry(MASK_HP, hp));
        }
        if (mp != null) {
            entries.add(new StatChangedPacket.StatEntry(MASK_MP, mp));
        }
        if (ap != null) {
            entries.add(new StatChangedPacket.StatEntry(MASK_AVAILABLE_AP, ap));
        }
        if (sp != null && !spTable) {
            entries.add(new StatChangedPacket.StatEntry(MASK_AVAILABLE_SP, visibleSp(sp)));
        }
        if (exp != null) {
            entries.add(new StatChangedPacket.StatEntry(MASK_EXP, Math.toIntExact(exp)));
        }
        boolean spBucketsUsed = spTable && sp != null;
        if (entries.isEmpty() && !unlockActions && !spBucketsUsed) {
            return List.of();   // 全空且无 unlock：不发包
        }
        // v83 语义：STAT_CHANGED 首字节 = 解除客户端动作锁——凡携带任何字段变更即自动置位
        // （原游戏侧逐处配对的 unlockActions() 下沉到版本层；显式 UnlockActionsEvent 仅剩
        //   纯解锁空包一条路径，即旧 enableActions 语义）。
        var spBuckets = spBucketsUsed
                ? StatChangedPacket.SpBuckets.of(
                        sp.spByJob().values().stream().mapToInt(Integer::intValue).toArray())
                : null;
        var packet = StatChangedPacket.of(true, entries, spBuckets);
        reset();
        return List.of(packet);
    }

    private void reset() {
        panel.clear();
        hp = mp = ap = null;
        sp = null;
        level = null;
        jobId = null;
        exp = null;
        unlockActions = false;
    }

    private static boolean spTableJob(int jobId) {
        JobEnum job = JobEnum.getById(jobId);
        return job != null && GameConstants.hasSPTable(job);
    }

    /** v83 客户端显示值：新手职业显示新手桶单值，其余显示非新手桶之和。 */
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
}
