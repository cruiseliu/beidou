package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventDest;
import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.modules.basic.server.BasicEvent;
import org.gms.remote.modules.basic.server.BasicUpdate;
import org.gms.remote.modules.basic.server.UnlockActionsEvent;

/**
 * 基础标识域 route：出脸（updateBasic/unlockActions）+ deliver 下沉。
 * Basic/UnlockActions 骑 stats 域的 STAT_CHANGED 包型（多对多映射归本类 deliver，statsT 注入）；
 * flush 为空——unlock 标志随 statsT 冲刷统一出包。
 */
public final class BasicRouter implements BasicModule, ServerEventDest {
    private final Gms083 client;

    public BasicRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    public void updateBasic(BasicUpdate update) {
        client.schedule(this, new BasicEvent(update));
    }

    @Override
    public void unlockActions() {
        client.schedule(this, new UnlockActionsEvent());
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case BasicEvent(var u) -> client.translators().statsT.onBasic(u);
            case UnlockActionsEvent ue -> client.translators().statsT.onUnlockActions();
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
