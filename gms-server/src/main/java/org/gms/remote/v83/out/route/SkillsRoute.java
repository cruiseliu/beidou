package org.gms.remote.v83.out.route;

import org.gms.remote.ScopeRecord;
import org.gms.remote.SkillUpdate;
import org.gms.remote.SkillsModule;
import org.gms.remote.SpUpdate;
import org.gms.remote.out.events.SemanticEvent;
import org.gms.remote.out.events.SkillEvent;
import org.gms.remote.out.events.SkillRemoveEvent;
import org.gms.remote.out.events.SpEvent;

import java.util.function.Consumer;

/** 技能域 route：模块调用 → 事件入域（SP 在 v83 下由 STAT_CHANGED 后端并包，映射归门面 deliver）。 */
public final class SkillsRoute implements SkillsModule {

    private final Consumer<SemanticEvent> dispatch;

    public SkillsRoute(Consumer<SemanticEvent> dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public void updateSp(SpUpdate update) {
        dispatch.accept(new SpEvent(update));
    }

    @Override
    public void updateSkill(SkillUpdate update) {
        dispatch.accept(new SkillEvent(update));
    }

    @Override
    public void removeSkill(int skillId) {
        dispatch.accept(new SkillRemoveEvent(skillId));
    }
}
