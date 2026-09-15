package org.gms.remote.gms083.server.translators;

import org.gms.remote.gms083.ServerTranslator;
import org.gms.remote.gms083.server.packets.CooldownPacket;
import org.gms.remote.gms083.server.packets.UpdateSkillsPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.modules.skills.server.SkillUpdate;

import java.util.ArrayList;
import java.util.List;

/** 技能域翻译：学习/更新/移除（level -1）+ 冷却清除（原 cooldown translator 并入）。 */
public final class SkillsTranslator implements ServerTranslator {
    private final List<UpdateSkillsPacket.SkillEntry> entries = new ArrayList<>();
    private final List<CooldownPacket> cooldownPackets = new ArrayList<>();

    /** 冷却清除（time=0，到期/重置）；每个清除一个包 */
    public void onCooldownClear(int skillId) {
        cooldownPackets.add(new CooldownPacket(skillId, (short) 0));
    }

    /** 冷却包冲刷（原 cooldownT.flush；调用方保持 skills → cooldown 的出包序） */
    public List<V83Packet> flushCooldown() {
        List<V83Packet> out = new ArrayList<>(cooldownPackets);
        cooldownPackets.clear();
        return out;
    }

    public void onSkill(SkillUpdate update) {
        // 非 4 转（jobId%10!=2 → skillId/10000%10 != 2）技能获得等级 0（已获得未分配 SP）时静默——
        // 客户端不需要更新通知（快照 addSkillInfo 会带上），建角/转职时机发出反而崩溃
        if (update.level() == 0 && (update.skillId() / 10000 % 10) != 2) {
            return;
        }
        entries.add(new UpdateSkillsPacket.SkillEntry(
                update.skillId(), update.level(), update.masterLevel(),
                Filetimes.toWire(update.expiration())));
    }

    public void onSkillRemove(int skillId) {
        entries.add(new UpdateSkillsPacket.SkillEntry(skillId, -1, 0, Filetimes.toWire(-1)));
    }

    @Override
    public List<V83Packet> flush() {
        if (entries.isEmpty()) {
            return List.of();   // 例：建角/转职的 level0 技能被静默过滤后，不得发出空帧
        }
        var packet = new UpdateSkillsPacket(entries);
        entries.clear();
        return List.of(packet);
    }
}
