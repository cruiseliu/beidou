package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventDest;
import org.gms.remote.modules.cooldown.CooldownModule;
import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.modules.cooldown.server.CooldownClearEvent;

/**
 * 冷却域 route：出脸（clearSkillCooldown）+ deliver/flush 下沉。
 */
public final class CooldownRouter implements CooldownModule, ServerEventDest {
    private final Gms083 client;

    public CooldownRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    public void clearSkillCooldown(int skillId) {
        client.schedule(this, new CooldownClearEvent(skillId));
    }

    @Override
    public void deliver(ServerEventBase r) {
        if (r instanceof CooldownClearEvent(int skillId)) {
            client.translators().cooldownT.onCooldownClear(skillId);
        }
    }

    @Override
    public void flush() {
        client.translators().cooldownT.flush().forEach(client::send);
    }
}
