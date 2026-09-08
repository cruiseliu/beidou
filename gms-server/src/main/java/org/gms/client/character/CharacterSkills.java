package org.gms.client.character;

import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.game.GameConstants;
import org.gms.model.pojo.SkillEntry;
import org.gms.net.server.PlayerCoolDownValueHolder;
import org.gms.net.server.Server;
import org.gms.remote.modules.skills.server.SkillUpdate;
import org.gms.util.Locks;
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
 * 技能变更/CD 公告走 remote 隔离层（updateSkill/removeSkill/clearSkillCooldown，skill 域）。Character 保留公开具名包装。
 */
class CharacterSkills {
    private final Character owner;

    /** skillId → SkillEntry（entry 持有 Skill 实例），加载/持久化路径包内直访 */
    final Map<Integer, SkillEntry> entries = new LinkedHashMap<>();
    final Map<Integer, CooldownStatus> cooldowns = new LinkedHashMap<>();

    /** CD 到期定时器（id=skillId，timestamp=到期时刻），替代原 1.5s 周期扫描 */
    private final TimeoutHelper cooldownTimer = new TimeoutHelper();
    /** 技能到期定时器（id=skillId，timestamp=过期时刻），到期自动移除技能 */
    private final TimeoutHelper skillExpireTimer = new TimeoutHelper();

    CharacterSkills(Character owner) {
        this.owner = owner;
        cooldownTimer.setListener((skillId, timestamp) -> {
            // fixme: [refactor] send packet in remove methods (it's outside because of battleship hack)
            removeCooldown(skillId);
            owner.remote().cooldown().clearSkillCooldown(skillId);
        });
        skillExpireTimer.setListener((skillId, timestamp) -> {
            removeSkill(skillId);
        });
    }

    Map<Integer, SkillEntry> getSkillsView() {
        return Collections.unmodifiableMap(entries);
    }

    int getSkillLevel(int skillId) {
        SkillEntry ret = entries.get(skillId);
        return ret == null ? 0 : ret.skillLevel;
    }

    long getSkillExpiration(int skillId) {
        SkillEntry ret = entries.get(skillId);
        return ret == null ? SkillEntry.PERMANENT : ret.expiration;
    }

    int getMasterLevel(int skillId) {
        SkillEntry ret = entries.get(skillId);
        return ret == null ? 0 : ret.masterLevel;
    }

    /** 是否已获得该技能（含等级 0 = 已获得未分配 SP；entries 即"已获得技能集"） */
    boolean hasSkill(int skillId) {
        return entries.containsKey(skillId);
    }

    /**
     * 变更已获得技能的等级/master/到期（skillId 内部解析为 Skill）。newLevel &ge; 0：
     * 等级 0 = 已获得但尚未分配 SP。不承担删除语义（到期清除/GM 重置走 {@link #removeSkill}）。
     */
    void changeSkillLevel(int skillId, int newLevel, int newMasterlevel, long expiration) {
        if (newLevel < 0) {
            throw new IllegalArgumentException("删除技能须走 removeSkill: " + skillId);
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("未登记的技能 id: " + skillId);
        }
        entries.put(skillId, new SkillEntry(skill, newLevel, newMasterlevel, expiration));
        if (expiration != SkillEntry.PERMANENT) {
            skillExpireTimer.schedule(skillId, expiration);
        } else {
            skillExpireTimer.cancel(skillId);
        }
        if (!GameConstants.isHiddenSkills(skillId)) {
            owner.remote().skills().updateSkill(new SkillUpdate(skillId, newLevel, newMasterlevel, expiration));
        }
    }

    /** 移除已获得的技能（技能到期自动清除 / GM 重置非本职业技能）。 */
    void removeSkill(int skillId) {
        entries.remove(skillId);
        skillExpireTimer.cancel(skillId);
        owner.remote().skills().removeSkill(skillId);
    }

    void startTimers() {
        cooldownTimer.start();
        skillExpireTimer.start();
        // 登录补排：加载期入库时定时器未激活、schedule 被 TimeoutHelper 丢弃，
        // 此处统一补排；已过期的由 scheduleOrTrigger 立即触发清除
        List<CooldownStatus> cds;
        List<Map.Entry<Integer, SkillEntry>> expiring = new ArrayList<>();
        try (var ignored = Locks.acquire(owner.chrLock)) {
            cds = new ArrayList<>(cooldowns.values());
            for (Map.Entry<Integer, SkillEntry> e : entries.entrySet()) {
                if (e.getValue().expiration != SkillEntry.PERMANENT) {
                    expiring.add(Map.entry(e.getKey(), e.getValue()));
                }
            }
        }
        for (CooldownStatus cd : cds) {
            cooldownTimer.scheduleOrTrigger(cd.skillId, cd.startTime + cd.length);
        }
        for (Map.Entry<Integer, SkillEntry> e : expiring) {
            skillExpireTimer.scheduleOrTrigger(e.getKey(), e.getValue().expiration);
        }
    }

    void stopTimers() {
        cooldownTimer.stop();
        skillExpireTimer.stop();
    }

    void addCooldown(int skillId, long startTime, long length) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            cooldowns.put(skillId, new CooldownStatus(skillId, startTime, length));
        }
        cooldownTimer.schedule(skillId, startTime + length);
    }

    // todo: [refactor] used by old login packet
    List<PlayerCoolDownValueHolder> getAllCooldowns() {
        List<PlayerCoolDownValueHolder> ret = new ArrayList<>();

        try (var ignored = Locks.acquire(owner.chrLock)) {
            for (CooldownStatus mcdvh : cooldowns.values()) {
                ret.add(new PlayerCoolDownValueHolder(mcdvh.skillId, mcdvh.startTime, mcdvh.length));
            }
        }

        return ret;
    }

    boolean skillIsCooling(int skillId) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            return cooldowns.containsKey(Integer.valueOf(skillId));
        }
    }

    void removeCooldown(int skillId) {
        cooldownTimer.cancel(skillId);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            cooldowns.remove(skillId);
        }
    }

    void removeAllCooldownsExcept(int id, boolean packet) {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            ArrayList<CooldownStatus> list = new ArrayList<>(cooldowns.values());
            for (CooldownStatus mcvh : list) {
                if (mcvh.skillId != id) {
                    cooldowns.remove(mcvh.skillId);
                    cooldownTimer.cancel(mcvh.skillId);
                    if (packet) {
                        owner.remote().cooldown().clearSkillCooldown(mcvh.skillId);
                    }
                }
            }
        }
    }

    // ── 持久化数据转换（skills 域；信封组装在 Character.toData/applyData） ──

    CharacterSkillsData toData() {
        CharacterSkillsData d = new CharacterSkillsData();
        for (Map.Entry<Integer, SkillEntry> e : entries.entrySet()) {
            CharacterSkillsData.SkillEntryData sd = new CharacterSkillsData.SkillEntryData();
            sd.level = e.getValue().skillLevel;
            sd.masterLevel = e.getValue().masterLevel;
            sd.expiration = e.getValue().expiration == SkillEntry.PERMANENT ? null : e.getValue().expiration;
            d.entries.put(e.getKey(), sd);
        }
        for (CooldownStatus cd : cooldowns.values()) {
            CharacterSkillsData.CooldownData cdd = new CharacterSkillsData.CooldownData();
            cdd.startTime = cd.startTime;
            cdd.length = cd.length;
            d.cooldowns.put(cd.skillId, cdd);
        }
        return d;
    }

    void applyData(CharacterSkillsData d) {
        // 反序列化只恢复状态、不调度：applyData 必须发生在定时器激活（startTimers）之前，
        // 否则"只填状态、不调度"的前提就不成立——先断言定时器未激活，杜绝"schedule 先于 start"重现。
        if (cooldownTimer.isActive() || skillExpireTimer.isActive()) {
            throw new IllegalStateException("CharacterSkills.applyData 必须在定时器激活前调用（反序列化期）");
        }
        entries.clear();
        for (Map.Entry<Integer, CharacterSkillsData.SkillEntryData> e : d.entries.entrySet()) {
            Skill skill = SkillFactory.getSkill(e.getKey());
            if (skill != null) {
                long expiration = e.getValue().expiration == null ? SkillEntry.PERMANENT : e.getValue().expiration;
                entries.put(e.getKey(), new SkillEntry(skill, e.getValue().level, e.getValue().masterLevel, expiration));
            }
        }
        // 反序列化只恢复状态、不调度：定时器尚未激活，schedule 会被 TimeoutHelper 丢弃（死代码）；
        // 真正的初次调度由 startTimers 的补排（scheduleOrTrigger）在激活时点统一完成。
        cooldowns.clear();
        long timeNow = Server.getInstance().getCurrentTime();
        try (var ignored = Locks.acquire(owner.chrLock)) {
            for (Map.Entry<Integer, CharacterSkillsData.CooldownData> e : d.cooldowns.entrySet()) {
                int skillId = e.getKey();
                long startTime = e.getValue().startTime;
                long length = e.getValue().length;
                if (skillId == 5221999) {   // 战船冷却槽复用为血量标记
                    owner.specialSkills.battleshipHp = (int) length;
                    cooldowns.put(skillId, new CooldownStatus(skillId, 0, length));
                } else {
                    int remaining = (int) ((length + startTime) - timeNow);
                    cooldowns.put(skillId, new CooldownStatus(skillId, timeNow, remaining));
                }
            }
        }
    }

    public static class CooldownStatus {
        public int skillId;
        public long startTime;
        public long length;

        public CooldownStatus(int skillId, long startTime, long length) {
            this.skillId = skillId;
            this.startTime = startTime;
            this.length = length;
        }
    }
}
