package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventDest;
import org.gms.remote.modules.skills.SkillsModule;
import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.modules.skills.server.SkillEvent;
import org.gms.remote.modules.skills.server.SkillRemoveEvent;
import org.gms.remote.modules.skills.server.SpEvent;
import org.gms.remote.modules.skills.server.SkillUpdate;
import org.gms.remote.modules.skills.server.SpUpdate;

/**
 * 技能域 route：出脸（updateSp/updateSkill/removeSkill）+ deliver/flush 下沉。
 * SP 在 v83 下由 STAT_CHANGED 后端并包（映射归本类 deliver）。
 */
public final class SkillsRouter implements SkillsModule, ServerEventDest {
    private final Gms083 client;

    public SkillsRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    public void updateSp(SpUpdate update) {
        client.schedule(this, new SpEvent(update));
    }

    @Override
    public void updateSkill(SkillUpdate update) {
        client.schedule(this, new SkillEvent(update));
    }

    @Override
    public void removeSkill(int skillId) {
        client.schedule(this, new SkillRemoveEvent(skillId));
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case SkillEvent(var u) -> client.translators().skillsT.onSkill(u);
            case SkillRemoveEvent(int skillId) -> client.translators().skillsT.onSkillRemove(skillId);
            case SpEvent(var u) -> client.translators().statsT.onSp(u);
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
        client.translators().skillsT.flush().forEach(client::send);
    }
}
