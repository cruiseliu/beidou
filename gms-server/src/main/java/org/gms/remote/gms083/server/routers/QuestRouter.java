package org.gms.remote.gms083.server.routers;

import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.packets.QuestInfoPacket;
import org.gms.remote.gms083.server.packets.QuestStatusPacket;
import org.gms.remote.modules.quest.QuestModule;
import org.gms.remote.modules.quest.server.QuestCompletedEvent;
import org.gms.remote.modules.quest.server.QuestExpiredEvent;
import org.gms.remote.modules.quest.server.QuestForfeitedEvent;
import org.gms.remote.modules.quest.server.QuestNpcDeliveryEvent;
import org.gms.remote.modules.quest.server.QuestStateEvent;
import org.gms.remote.modules.quest.server.QuestTimeLimitEvent;
import org.gms.remote.modules.quest.server.QuestTimeLimitRemovedEvent;

/**
 * 任务域 route：出脸继承自 {@link QuestModule}（API → 事件在基类），本类承载
 * emit/deliver/flush——语义事件 → quest 包 record 直发本连接（无 translator，
 * 事件即编码事实；无合并冲刷需求，flush 恒空）。
 */
public final class QuestRouter extends QuestModule implements ServerEventDest {
    private final Gms083 client;

    public QuestRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case QuestStateEvent(var questId, var status, var progressData) ->
                    client.send(new QuestStatusPacket(new QuestStatusPacket.Body.Update(questId, status, progressData)));
            case QuestCompletedEvent(var questId, var completionTime) ->
                    client.send(new QuestStatusPacket(new QuestStatusPacket.Body.Completed(questId, completionTime)));
            case QuestForfeitedEvent(var questId) ->
                    client.send(new QuestStatusPacket(new QuestStatusPacket.Body.Forfeit(questId)));
            case QuestNpcDeliveryEvent(var questId, var npc) ->
                    client.send(new QuestInfoPacket(new QuestInfoPacket.Body.NpcDelivery(questId, npc)));
            case QuestTimeLimitEvent(var questId, var remainingMillis) ->
                    client.send(new QuestInfoPacket(new QuestInfoPacket.Body.TimeLimitAdded(questId, remainingMillis)));
            case QuestTimeLimitRemovedEvent(var questId) ->
                    client.send(new QuestInfoPacket(new QuestInfoPacket.Body.TimeLimitRemoved(questId)));
            case QuestExpiredEvent(var questId) ->
                    client.send(new QuestInfoPacket(new QuestInfoPacket.Body.Expired(questId)));
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
