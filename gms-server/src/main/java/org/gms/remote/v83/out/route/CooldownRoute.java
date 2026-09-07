package org.gms.remote.v83.out.route;

import org.gms.remote.CooldownModule;
import org.gms.remote.out.events.CooldownClearEvent;
import org.gms.remote.out.events.SemanticEvent;

import java.util.function.Consumer;

/** 冷却域 route：模块调用 → 事件入域。 */
public final class CooldownRoute implements CooldownModule {

    private final Consumer<SemanticEvent> dispatch;

    public CooldownRoute(Consumer<SemanticEvent> dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public void clearSkillCooldown(int skillId) {
        dispatch.accept(new CooldownClearEvent(skillId));
    }
}
