package org.gms.remote;

/** 语义模块：域归属见类型注释；wire 组装归后端私有（多对多约束见 doc/09 §5.2）。 */
/** 技能域：SP/技能等级/master/到期/移除。SP 在 v83 下由 STAT_CHANGED 后端并包。 */
public interface SkillsModule {
    void updateSp(SpUpdate update);

    void updateSkill(SkillUpdate update);

    /** 客户端侧删除已获得技能 */
    void removeSkill(int skillId);
}
