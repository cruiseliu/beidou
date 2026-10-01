package org.gms.remote.modules.skills;

import org.gms.client.SkillMacro;
import org.gms.remote.AbstractModule;
import org.gms.remote.modules.skills.server.CooldownClearEvent;
import org.gms.remote.modules.skills.server.MacrosEvent;
import org.gms.remote.modules.skills.server.SkillEvent;
import org.gms.remote.modules.skills.server.SkillRemoveEvent;
import org.gms.remote.modules.skills.server.SkillUpdate;
import org.gms.remote.modules.skills.server.SpEvent;
import org.gms.remote.modules.skills.server.SpUpdate;

/** 技能域（语义基类）：SP/技能等级/master/到期/移除 + 冷却清除（原 cooldown 域并入）。 */
public abstract class SkillsModule extends AbstractModule {

    public final void updateSp(SpUpdate update) {
        post(new SpEvent(update));
    }

    public final void updateSkill(SkillUpdate update) {
        post(new SkillEvent(update));
    }

    /** 客户端侧删除已获得技能 */
    public final void removeSkill(int skillId) {
        post(new SkillRemoveEvent(skillId));
    }

    /** 清除技能冷却显示（到期/重置；原 cooldown 域并入） */
    public final void clearSkillCooldown(int skillId) {
        post(new CooldownClearEvent(skillId));
    }

    /** 技能宏表重推（SP 重置清引用等运行期变更；与入图初始化同一 wire 包） */
    public final void updateMacros(SkillMacro[] macros) {
        post(new MacrosEvent(macros));
    }
}
