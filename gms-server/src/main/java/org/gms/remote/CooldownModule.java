package org.gms.remote;

/** 冷却域语义模块（多对多映射见 gms-server/doc/package-client.md §3）。 */
public interface CooldownModule {
    /** 清除技能冷却显示（到期/重置） */
    void clearSkillCooldown(int skillId);
}
