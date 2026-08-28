package org.gms.remote;

/** 冷却域语义模块（wire 组装归后端私有，多对多约束见 doc/09 §5.2）。 */
public interface CooldownModule {
    /** 清除技能冷却显示（到期/重置） */
    void clearSkillCooldown(int skillId);
}
