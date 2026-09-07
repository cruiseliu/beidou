package org.gms.remote.v83.out.translate;

import org.gms.remote.SkillUpdate;
import org.gms.remote.v83.out.packet.UpdateSkillsPacket;
import org.gms.remote.v83.out.packet.V83Packet;

import java.util.ArrayList;
import java.util.List;

/** 技能域翻译：学习/更新/移除（level -1）。 */
public final class SkillsTranslator implements Translator {
    private final List<UpdateSkillsPacket.SkillEntry> entries = new ArrayList<>();

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
    public boolean isEmpty() {
        return entries.isEmpty();
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
