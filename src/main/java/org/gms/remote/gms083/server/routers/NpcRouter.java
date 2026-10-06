package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.packets.ServerMessagePacket;
import org.gms.remote.modules.npc.NpcModule;
import org.gms.remote.modules.npc.server.NpcTalkEvent;
import org.gms.remote.modules.npc.server.ServerNoticeEvent;

/**
 * NPC 对话域 route：出脸继承自 {@link NpcModule}（API → 事件在基类），本类承载 emit/deliver/flush
 * ——buttons 语义 → wire 映射（MSG_TALK/MSG_YES_NO/MSG_ACCEPT_DECLINE）归 NpcTranslator。
 */
public final class NpcRouter extends NpcModule implements ServerEventDest {
    private final Gms083 client;

    public NpcRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case NpcTalkEvent(var npc, var text, var buttons, var speaker) ->
                    client.send(client.translators().npcT.talk(npc, text, buttons, speaker));
            case ServerNoticeEvent(var type, var message) -> client.send(new ServerMessagePacket(type, message));
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
