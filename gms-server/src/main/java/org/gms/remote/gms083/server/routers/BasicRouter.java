package org.gms.remote.gms083.server.routers;

import org.gms.client.Client;
import org.gms.client.character.Character;
import org.gms.net.server.Server;
import org.gms.remote.ServerEventDest;
import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.translators.KeymapTranslator;
import org.gms.remote.gms083.server.translators.MacrosTranslator;
import org.gms.remote.gms083.server.translators.QuickslotTranslator;
import org.gms.remote.gms083.server.translators.SetFieldTranslator;
import org.gms.remote.modules.basic.server.BasicEvent;
import org.gms.remote.modules.basic.server.BasicUpdate;
import org.gms.remote.modules.basic.server.InitializeEvent;
import org.gms.remote.modules.basic.server.MacrosEvent;
import org.gms.remote.modules.basic.server.UnlockActionsEvent;

/**
 * 基础标识域 route：出脸（updateBasic/unlockActions/initialize）+ deliver 下沉。
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

    /** 直发改 schedule（doc/12 追记 10）：无开域即时 deliver，开域入段回放 */
    @Override
    public void initialize(Character chr) {
        client.schedule(this, new InitializeEvent(chr));
    }

    /** 技能宏表重推：入域即浅冻结（MacrosEvent 构造期数组克隆） */
    @Override
    public void updateMacros(org.gms.client.SkillMacro[] macros) {
        client.schedule(this, new MacrosEvent(macros));
    }

    private void onInitialize(InitializeEvent event) {
        // 不完整 freeze：chr 活引用 + wire 事实（channel/buddy/linkedName/meso/时间）在
        // deliver 时点派生——合并域内提交晚于构造会读到未来状态。完整冻结（入域时快照）
        // 以后再修。
        Character chr = event.chr();
        Client c = client.getLegacyClient();
        client.send(SetFieldTranslator.setField(chr,
                c.getChannel() - 1,
                chr.getBuddylist().getCapacity(),
                chr.getLinkedName(),
                chr.getMeso(),
                Server.getInstance().getCurrentTime()));
        client.send(KeymapTranslator.keymap(chr.getKeymap()));
        client.send(QuickslotTranslator.quickslot(chr.getQuickSlotKeyMapped()));
        client.send(MacrosTranslator.macros(chr.getMacros()));
        client.send(KeymapTranslator.autoHpPot(chr.getKeymap()));
        client.send(KeymapTranslator.autoMpPot(chr.getKeymap()));
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case InitializeEvent e -> onInitialize(e);
            case MacrosEvent m -> client.send(MacrosTranslator.macros(m.macros()));
            case BasicEvent(var u) -> client.translators().statsT.onBasic(u);
            case UnlockActionsEvent ue -> client.translators().statsT.onUnlockActions();
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
