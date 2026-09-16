package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.packets.PlayerHintPacket;
import org.gms.remote.gms083.server.packets.StatChangedPacket;
import org.gms.remote.modules.message.MessageModule;
import org.gms.remote.modules.message.server.ShowHintEvent;

/**
 * 提示消息域 route：出脸继承自 {@link MessageModule}（API → 事件在基类），本类承载
 * emit/deliver/flush。ShowHint 一事件落两包（多对多下沉，PetPanel 先例）：PLAYER_HINT
 * + unlock STAT_CHANGED——脚本门场景客户端锁输入，解锁随提示语义走，gameplay 无感。
 */
public final class MessageRouter extends MessageModule implements ServerEventDest {
    private final Gms083 client;

    public MessageRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case ShowHintEvent(var message, var width, var height) -> {
                client.send(new PlayerHintPacket(message, width, height));
                client.send(StatChangedPacket.unlock());
            }
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
