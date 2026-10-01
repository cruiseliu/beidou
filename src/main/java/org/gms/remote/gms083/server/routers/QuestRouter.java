package org.gms.remote.gms083.server.routers;

import org.gms.client.quest.Quest;
import org.gms.remote.ServerEvent;
import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.events.FrozenQuestCompleteEvent;
import org.gms.remote.gms083.server.events.FrozenQuestForfeitEvent;
import org.gms.remote.gms083.server.events.FrozenQuestStartEvent;
import org.gms.remote.gms083.server.packets.QuestInfoPacket;
import org.gms.remote.gms083.server.packets.QuestStatusPacket;
import org.gms.remote.gms083.server.packets.ShowItemGainInchatPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.gms083.server.translators.QuestProgressFormat;
import org.gms.remote.modules.quest.QuestModule;
import org.gms.remote.modules.quest.server.QuestCompleteEvent;
import org.gms.remote.modules.quest.server.QuestExpiredEvent;
import org.gms.remote.modules.quest.server.QuestForfeitEvent;
import org.gms.remote.modules.quest.server.QuestSeriesCompleteEvent;
import org.gms.remote.modules.quest.server.QuestStartEvent;
import org.gms.remote.modules.quest.server.QuestStateEvent;
import org.gms.remote.modules.quest.server.QuestTimeLimitEvent;
import org.gms.remote.modules.quest.server.QuestTimeLimitRemovedEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务域 route：出脸继承自 {@link QuestModule}（API → 事件在基类），本类承载统一冻结门
 * + emit/deliver/flush——实体档事件经冻结门在入域时点物化为成品帧（QuestStartEvent →
 * FrozenQuestStartEvent：主任务状态帧 + infoNumber 关联任务同步 + 交付确认；
 * QuestCompleteEvent → FrozenQuestCompleteEvent：完成状态帧 + 完成演出帧；
 * QuestForfeitEvent → FrozenQuestForfeitEvent：放弃状态帧 + 关联任务同步；wire 事实读于
 * 调用时点）；其余语义事件 → quest 包 record 直发本连接（无 translator，事件即编码事实；
 * 无合并冲刷需求，flush 恒空）。
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

    /** 统一冻结门：接取/完成全量帧在事件构造时点物化（其余事件恒等通过） */
    @Override
    protected ServerEventBase freeze(ServerEvent event) {
        if (event instanceof QuestStartEvent start) {
            return freezeStart(start);
        }
        if (event instanceof QuestCompleteEvent complete) {
            return freezeComplete(complete);
        }
        return event;
    }

    private FrozenQuestStartEvent freezeStart(QuestStartEvent start) {
        Quest quest = start.quest();
        List<V83Packet> frames = new ArrayList<>(3);
        frames.add(new QuestStatusPacket(new QuestStatusPacket.Body.Update(
                quest.getId(), quest.getStatus().getValue(), QuestProgressFormat.toWire(quest.getProgress()))));
        Quest info = quest.getInfo();
        if (info != null) {
            frames.add(new QuestStatusPacket(new QuestStatusPacket.Body.Update(
                    info.getId(), info.getStatus().getValue(), QuestProgressFormat.toWire(info.getProgress()))));
        }
        frames.add(new QuestInfoPacket(new QuestInfoPacket.Body.NpcDelivery(quest.getId(), quest.getNpc())));
        return new FrozenQuestStartEvent(frames);
    }

    private FrozenQuestCompleteEvent freezeComplete(QuestCompleteEvent complete) {
        Quest quest = complete.quest();
        List<V83Packet> frames = List.of(
                new QuestStatusPacket(new QuestStatusPacket.Body.Completed(quest.getId(), quest.getCompletionTime())),
                // 效果码 9 = 任务完成（本人 SHOW_ITEM_GAIN_INCHAT；全图 SHOW_FOREIGN_EFFECT 归地图广播）
                new ShowItemGainInchatPacket(new ShowItemGainInchatPacket.Body.Effect((byte) 9)));
        return new FrozenQuestCompleteEvent(frames);
    }

    private FrozenQuestForfeitEvent freezeForfeit(QuestForfeitEvent event) {
        Quest quest = event.quest();
        List<V83Packet> frames = new ArrayList<>(2);
        frames.add(new QuestStatusPacket(new QuestStatusPacket.Body.Update(
                quest.getId(), quest.getStatus().getValue(), QuestProgressFormat.toWire(quest.getProgress()))));
        // 关联任务状态原样同步（进度不清——部分任务不可回退）
        Quest info = quest.getInfo();
        if (info != null) {
            frames.add(new QuestStatusPacket(new QuestStatusPacket.Body.Update(
                    info.getId(), info.getStatus().getValue(), QuestProgressFormat.toWire(info.getProgress()))));
        }
        return new FrozenQuestForfeitEvent(frames);
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case FrozenQuestStartEvent f -> f.frames().forEach(client::send);
            case FrozenQuestCompleteEvent f -> f.frames().forEach(client::send);
            case FrozenQuestForfeitEvent f -> f.frames().forEach(client::send);
            case QuestStateEvent(var questId, var status, var progress) ->
                    client.send(new QuestStatusPacket(new QuestStatusPacket.Body.Update(
                            questId, status, QuestProgressFormat.toWire(progress))));
            case QuestSeriesCompleteEvent(var questId, var npc) ->
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
