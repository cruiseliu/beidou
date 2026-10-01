package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.translators.MacrosTranslator;
import org.gms.remote.modules.skills.SkillsModule;
import org.gms.remote.modules.skills.server.CooldownClearEvent;
import org.gms.remote.modules.skills.server.MacrosEvent;
import org.gms.remote.modules.skills.server.SkillEvent;
import org.gms.remote.modules.skills.server.SkillRemoveEvent;
import org.gms.remote.modules.skills.server.SpEvent;

/**
 * 技能域 route：出脸继承自 {@link SkillsModule}（SP/技能等级/移除/冷却清除/宏表 → 事件在基类），
 * 本类承载 emit/deliver/flush。SP 在 v83 下由 STAT_CHANGED 后端并包（映射归本类 deliver）。
 */
public final class SkillsRouter extends SkillsModule implements ServerEventDest {
    private final Gms083 client;

    public SkillsRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case SkillEvent(var u) -> client.translators().skillsT.onSkill(u);
            case SkillRemoveEvent(int skillId) -> client.translators().skillsT.onSkillRemove(skillId);
            case SpEvent(var u) -> client.translators().statsT.onSp(u);
            case CooldownClearEvent(int skillId) -> client.translators().skillsT.onCooldownClear(skillId);
            case MacrosEvent m -> client.send(MacrosTranslator.macros(m.macros()));
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    /** 冲刷序保持原 flushAll 相对序：技能包在前，冷却包随后（原 cooldown router 段并入） */
    @Override
    public void flush() {
        client.translators().skillsT.flush().forEach(client::send);
        client.translators().skillsT.flushCooldown().forEach(client::send);
    }
}
