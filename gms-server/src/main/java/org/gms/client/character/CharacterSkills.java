package org.gms.client.character;

import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.game.GameConstants;
import org.gms.model.pojo.SkillEntry;
import org.gms.net.server.PlayerCoolDownValueHolder;
import org.gms.net.server.Server;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;
import org.gms.model.json.CharacterSkillsData;
import org.gms.util.TimeoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能等级与技能 CD：数据 + 管理逻辑（查询/变更/CD 定时/持久化）。
 * 持有 owner 反向引用，CD 并发用 Locks 取 owner 的 chrLock（每角色战斗状态域，非 buff 域），
 * 技能变更发包走 owner.sendPacket。Character 保留公开具名包装。
 */
class CharacterSkills {
    private final Character owner;

    /** Skill → SkillEntry，加载/持久化路径包内直访 */
    final Map<Skill, SkillEntry> entries = new LinkedHashMap<>();
    final Map<Integer, CooldownValueHolder> coolDowns = new LinkedHashMap<>();

    /** CD 到期定时器（id=skillId，timestamp=到期时刻），替代原 1.5s 周期扫描 */
    private final TimeoutHelper cooldownTimer = new TimeoutHelper();
    /** 技能到期定时器（id=skillId，timestamp=过期时刻），到期自动移除技能 */
    private final TimeoutHelper skillExpireTimer = new TimeoutHelper();

    CharacterSkills(Character owner) {
        this.owner = owner;
        cooldownTimer.setListener((skillId, timestamp) -> {
            removeCooldown(skillId);
            owner.sendPacket(PacketCreator.skillCooldown(skillId, 0));
        });
        skillExpireTimer.setListener((skillId, timestamp) ->
                changeSkillLevel(SkillFactory.getSkill(skillId), -1, 0, -1));
    }

    Map<Skill, SkillEntry> getSkillsView() {
        return Collections.unmodifiableMap(entries);
    }

    int getSkillLevel(int skill) {
        SkillEntry ret = entries.get(SkillFactory.getSkill(skill));
        if (ret == null) {
            return 0;
        }
        return ret.skillLevel;
    }

    int getSkillLevel(Skill skill) {
        if (entries.get(skill) == null) {
            return 0;
        }
        return entries.get(skill).skillLevel;
    }

    long getSkillExpiration(int skill) {
        SkillEntry ret = entries.get(SkillFactory.getSkill(skill));
        if (ret == null) {
            return -1;
        }
        return ret.expiration;
    }

    long getSkillExpiration(Skill skill) {
        if (entries.get(skill) == null) {
            return -1;
        }
        return entries.get(skill).expiration;
    }

    int getMasterLevel(int skill) {
        SkillEntry ret = entries.get(SkillFactory.getSkill(skill));
        if (ret == null) {
            return 0;
        }
        return ret.masterLevel;
    }

    int getMasterLevel(Skill skill) {
        if (entries.get(skill) == null) {
            return 0;
        }
        return entries.get(skill).masterLevel;
    }

    void changeSkillLevel(Skill skill, int newLevel, int newMasterlevel, long expiration) {
        if (newLevel > -1) {
            entries.put(skill, new SkillEntry(newLevel, newMasterlevel, expiration));
            if (expiration != -1) {
                skillExpireTimer.schedule(skill.getId(), expiration);
            } else {
                skillExpireTimer.cancel(skill.getId());
            }
            if (!GameConstants.isHiddenSkills(skill.getId())) {
                owner.sendPacket(PacketCreator.updateSkill(skill.getId(), newLevel, newMasterlevel, expiration));
            }
        } else {
            entries.remove(skill);
            skillExpireTimer.cancel(skill.getId());
            owner.sendPacket(PacketCreator.updateSkill(skill.getId(), newLevel, newMasterlevel, -1)); //Shouldn't use expiration anymore :)
        }
    }

    void startTimers() {
        cooldownTimer.start();
        skillExpireTimer.start();
        // 登录补排：加载期入库时定时器未激活、schedule 被 TimeoutHelper 丢弃，
        // 此处统一补排；已过期的由 scheduleOrTrigger 立即触发清除
        List<CooldownValueHolder> cds;
        List<Map.Entry<Skill, SkillEntry>> expiring = new ArrayList<>();
        try (var ignored = Locks.acquire(owner.chrLock)) {
            cds = new ArrayList<>(coolDowns.values());
            for (Map.Entry<Skill, SkillEntry> e : entries.entrySet()) {
                if (e.getValue().expiration != -1) {
                    expiring.add(Map.entry(e.getKey(), e.getValue()));
                }
            }
        }
        for (CooldownValueHolder cd : cds) {
            cooldownTimer.scheduleOrTrigger(cd.skillId, cd.startTime + cd.length);
        }
        for (Map.Entry<Skill, SkillEntry> e : expiring) {
            skillExpireTimer.scheduleOrTrigger(e.getKey().getId(), e.getValue().expiration);
        }
    }

    void stopTimers() {
        cooldownTimer.stop();
        skillExpireTimer.stop();
    }

    void addCooldown(int skillId, long startTime, long length) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            coolDowns.put(skillId, new CooldownValueHolder(skillId, startTime, length));
        }
        cooldownTimer.schedule(skillId, startTime + length);
    }

    void giveCoolDowns(final int skillid, long starttime, long length) {
        if (skillid == 5221999) {
            owner.battleshipHp = (int) length;
            addCooldown(skillid, 0, length);
        } else {
            long timeNow = Server.getInstance().getCurrentTime();
            int time = (int) ((length + starttime) - timeNow);
            addCooldown(skillid, timeNow, time);
        }
    }

    List<PlayerCoolDownValueHolder> getAllCooldowns() {
        List<PlayerCoolDownValueHolder> ret = new ArrayList<>();

        try (var ignored = Locks.acquire(owner.chrLock)) {
            for (CooldownValueHolder mcdvh : coolDowns.values()) {
                ret.add(new PlayerCoolDownValueHolder(mcdvh.skillId, mcdvh.startTime, mcdvh.length));
            }
        }

        return ret;
    }

    boolean skillIsCooling(int skillId) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            return coolDowns.containsKey(Integer.valueOf(skillId));
        }
    }

    void removeCooldown(int skillId) {
        cooldownTimer.cancel(skillId);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            coolDowns.remove(skillId);
        }
    }

    void removeAllCooldownsExcept(int id, boolean packet) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            ArrayList<CooldownValueHolder> list = new ArrayList<>(coolDowns.values());
            for (CooldownValueHolder mcvh : list) {
                if (mcvh.skillId != id) {
                    coolDowns.remove(mcvh.skillId);
                    cooldownTimer.cancel(mcvh.skillId);
                    if (packet) {
                        owner.sendPacket(PacketCreator.skillCooldown(mcvh.skillId, 0));
                    }
                }
            }
        }
    }

    // ── 持久化数据转换（skills 域；信封组装在 Character.toData/applyData） ──

    CharacterSkillsData toData() {
        CharacterSkillsData d = new CharacterSkillsData();
        for (Map.Entry<Skill, SkillEntry> e : entries.entrySet()) {
            CharacterSkillsData.SkillEntryData sd = new CharacterSkillsData.SkillEntryData();
            sd.level = e.getValue().skillLevel;
            sd.masterLevel = e.getValue().masterLevel;
            sd.expiration = e.getValue().expiration == -1 ? null : e.getValue().expiration;
            d.entries.put(e.getKey().getId(), sd);
        }
        for (CooldownValueHolder cd : coolDowns.values()) {
            CharacterSkillsData.CooldownData cdd = new CharacterSkillsData.CooldownData();
            cdd.startTime = cd.startTime;
            cdd.length = cd.length;
            d.cooldowns.put(cd.skillId, cdd);
        }
        return d;
    }

    void applyData(CharacterSkillsData d) {
        entries.clear();
        for (Map.Entry<Integer, CharacterSkillsData.SkillEntryData> e : d.entries.entrySet()) {
            Skill skill = SkillFactory.getSkill(e.getKey());
            if (skill != null) {
                long expiration = e.getValue().expiration == null ? -1 : e.getValue().expiration;
                entries.put(skill, new SkillEntry(e.getValue().level, e.getValue().masterLevel, expiration));
            }
        }
        coolDowns.clear();
        for (Map.Entry<Integer, CharacterSkillsData.CooldownData> e : d.cooldowns.entrySet()) {
            giveCoolDowns(e.getKey(), e.getValue().startTime, e.getValue().length);
        }
    }

    public static class CooldownValueHolder {
        public int skillId;
        public long startTime, length;

        public CooldownValueHolder(int skillId, long startTime, long length) {
            this.skillId = skillId;
            this.startTime = startTime;
            this.length = length;
        }
    }
}
