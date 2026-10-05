package org.gms.remote.gms083.server.routers;

import org.gms.client.Client;
import org.gms.client.character.Character;
import org.gms.net.server.Server;
import org.gms.remote.ServerEvent;
import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.events.FrozenInitializeEvent;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.gms083.server.translators.KeymapTranslator;
import org.gms.remote.gms083.server.translators.MacrosTranslator;
import org.gms.remote.gms083.server.translators.QuickslotTranslator;
import org.gms.remote.gms083.server.translators.ExpGainTranslator;
import org.gms.remote.gms083.server.translators.SetFieldTranslator;
import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.modules.basic.server.InitializeEvent;
import org.gms.remote.modules.basic.server.GainExpEvent;
import org.gms.remote.modules.basic.server.UnlockActionsEvent;
import org.gms.remote.modules.basic.server.UpdateExpEvent;
import org.gms.remote.modules.basic.server.UpdateJobEvent;
import org.gms.remote.modules.basic.server.UpdateLevelEvent;

import java.util.List;

/**
 * 基础标识域 route：出脸继承自 {@link BasicModule}（API → 事件在基类），本类承载统一冻结门
 * + emit + deliver + flush。InitializeEvent（活引用档）经统一冻结门在入域时点物化为成品帧
 * FrozenInitializeEvent——wire 事实（channel/buddyCapacity/linkedName/meso/时间）读于调用时点，
 * 合并域内提交晚于构造不再读到未来状态。
 * UpdateJob/Level/Exp/UnlockActions 骑 stats 域的 STAT_CHANGED 包型（多对多映射归 deliver，
 * statsT 注入）；flush 为空——unlock 标志随 statsT 冲刷统一出包。
 */
public final class BasicRouter extends BasicModule implements ServerEventDest {
    private final Gms083 client;

    public BasicRouter(Gms083 client) {
        this.client = client;
    }

    /** 唯一出口：接事务机器（doc/12 追记 10：无开域即时 deliver，开域入段回放） */
    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    /** 统一冻结门：入场初始化帧在事件构造时点物化（其余事件恒等通过） */
    @Override
    protected ServerEventBase freeze(ServerEvent event) {
        if (!(event instanceof InitializeEvent init)) {
            return event;
        }
        Character chr = init.chr();
        Client c = client.getLegacyClient();
        List<V83Packet> frames = List.of(
                SetFieldTranslator.setField(chr,
                        c.getChannel() - 1,
                        chr.getBuddylist().getCapacity(),
                        chr.getLinkedName(),
                        chr.getMeso(),
                        Server.getInstance().getCurrentTime()),
                KeymapTranslator.keymap(chr.getKeymap()),
                QuickslotTranslator.quickslot(chr.getQuickSlotKeyMapped()),
                MacrosTranslator.macros(chr.getMacros()),
                KeymapTranslator.autoHpPot(chr.getKeymap()),
                KeymapTranslator.autoMpPot(chr.getKeymap()));
        return new FrozenInitializeEvent(frames);
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case FrozenInitializeEvent f -> f.frames().forEach(client::send);
            case UpdateJobEvent(var jobId) -> client.translators().statsT.onJob(jobId);
            case UpdateLevelEvent(var level) -> client.translators().statsT.onLevel(level);
            case UpdateExpEvent(var exp) -> client.translators().statsT.onExp(exp);
            case UnlockActionsEvent ue -> client.translators().statsT.onUnlockActions();
            case GainExpEvent(var gain, var source) ->
                    client.send(client.translators().expGainT.display(gain, source));
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
