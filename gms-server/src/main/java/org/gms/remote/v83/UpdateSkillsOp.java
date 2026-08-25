package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;
import org.gms.remote.SkillUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * UPDATE_SKILLS（0x24）：技能等级/master/到期更新的 count 列表包
 * （自 PacketCreator.updateSkill 逐字节移植；wire 自带 count，同域多条合并为一包）。
 * removeSkill 复用本 op（编码为 level -1 的条目）。
 */
final class UpdateSkillsOp implements V83Op {
    private final List<SkillUpdate> updates = new ArrayList<>();

    void merge(SkillUpdate update) {
        // 非 4 转（jobId % 10 != 2）技能获得等级 0（已获得未分配 SP）时静默——
        // 客户端不需要更新通知（快照 addSkillInfo 会带上），建角/转职时机发出反而崩溃
        if (update.level() == 0 && (update.skillId() / 10000 % 10) != 2) {
            return;
        }
        updates.add(update);
    }

    void mergeRemove(int skillId) {
        updates.add(new SkillUpdate(skillId, -1, 0, -1));
    }

    @Override
    public boolean isEmpty() {
        return updates.isEmpty();
    }

    @Override
    public void sendTo(Client client) {
        if (updates.isEmpty()) {
            return;
        }
        client.sendPacket(encode());
        updates.clear();
    }

    // ── v83 filetime 换算（自 PacketCreator.getTime 移植；-1/-2/-3 为特殊哨兵值）──
    private static final long FT_UT_OFFSET = 116444736010800000L + (10000L * TimeZone.getDefault().getOffset(System.currentTimeMillis()));
    private static final long DEFAULT_TIME = 150842304000000000L;
    private static final long ZERO_TIME = 94354848000000000L;
    private static final long FT_PERMANENT = 150841440000000000L;

    private static long filetime(long utcTimestamp) {
        if (utcTimestamp < 0 && utcTimestamp >= -3) {
            if (utcTimestamp == -1) {
                return DEFAULT_TIME;
            } else if (utcTimestamp == -2) {
                return ZERO_TIME;
            } else {
                return FT_PERMANENT;
            }
        }
        return utcTimestamp * 10000 + FT_UT_OFFSET;
    }

    private Packet encode() {
        OutPacket p = OutPacket.create(SendOpcode.UPDATE_SKILLS);
        p.writeByte(1);
        p.writeShort(updates.size());
        for (SkillUpdate u : updates) {
            p.writeInt(u.skillId());
            p.writeInt(u.level());
            p.writeInt(u.masterLevel());
            p.writeLong(filetime(u.expiration()));
        }
        p.writeByte(4);
        return p;
    }
}
