package org.gms.client.scripting.api;

import org.gms.client.Player;
import org.gms.client.scripting.InteractContext;
import org.gms.remote.modules.npc.client.DialogButtons;

/**
 * 脚本 API 白名单——对话页面（send* 族）。对话 npc 归属会话：首参收
 * {@link InteractContext}，npc id 取 {@code ctx.getNpcId()}（会话身份即对话归属；
 * 脚本 npc 与会话 npc 的一致性已在开场断言，见 interaction.js #begin）。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败）。self 流经 remote
 * npc 模块编码直发本连接（样式字节契约见 NpcModule）；wire 禁令合规——脚本不触达
 * PacketCreator（doc/script-engine.md §4.7）。
 */
public final class TalkApi {

    /** 单按钮"下一步"（00 01，gms083 实测） */
    public void sendNext(InteractContext ctx, String text) {
        Player.require("TalkApi.sendNext").character().getRemote().npc().talk(ctx.getNpcId(), text, DialogButtons.NEXT, 0);
    }

    /** 单按钮"上一步"（01 00，gms083 实测）；按钮点击即整链收尾（原版 sendPrev 页语义） */
    public void sendPrevOk(InteractContext ctx, String text) {
        Player.require("TalkApi.sendPrevOk").character().getRemote().npc().talk(ctx.getNpcId(), text, DialogButtons.PREV_OK, 0);
    }

    /** 双按钮"上一步/下一步"（01 01，gms083 实测） */
    public void sendPrevNext(InteractContext ctx, String text) {
        Player.require("TalkApi.sendPrevNext").character().getRemote().npc().talk(ctx.getNpcId(), text, DialogButtons.PREV_NEXT, 0);
    }

    /** 单按钮"确定" */
    public void sendOk(InteractContext ctx, String text) {
        Player.require("TalkApi.sendOk").character().getRemote().npc().talk(ctx.getNpcId(), text, DialogButtons.OK, 0);
    }

    /** 是/否（mode 1 = 是，0 = 否） */
    public void sendYesNo(InteractContext ctx, String text) {
        Player.require("TalkApi.sendYesNo").character().getRemote().npc().talk(ctx.getNpcId(), text, DialogButtons.YES_NO, 0);
    }

    /** 接受/拒绝（mode 1 = 接受，0 = 拒绝） */
    public void sendAcceptDecline(InteractContext ctx, String text) {
        Player.require("TalkApi.sendAcceptDecline").character().getRemote().npc().talk(ctx.getNpcId(), text, DialogButtons.ACCEPT_DECLINE, 0);
    }

    /**
     * 统一对话页入口（脚本侧首选）：buttons 取 {@link DialogButtons} 枚举名字符串
     * （"NEXT"/"PREV_OK"/"PREV_NEXT"/"OK"/"YES_NO"/"ACCEPT_DECLINE"），内部手动
     * valueOf 转换——GraalJS 不做 string→enum 自动转换（GraalEnumProbe 实测
     * "Unsupported target type"）；非法名 valueOf 抛 IllegalArgumentException，响亮失败。
     */
    public void send(InteractContext ctx, String text, String buttons) {
        talk(ctx, text, DialogButtons.valueOf(buttons));
    }

    /**
     * 终结对话（原 InteractContext.dispose 的脚本面迁移；ctx 对 JS 为 opaque 令牌，
     * 方法面不对 JS 开放）：解除会话登记（幂等——仅当仍登记同一实例时生效）。
     */
    public void end(InteractContext ctx) {
        Player.require("TalkApi.end").character().getNpcInteract().clearContext(ctx);
    }

    private void talk(InteractContext ctx, String text, DialogButtons buttons) {
        Player.require("TalkApi.send").character().getRemote().npc().talk(ctx.getNpcId(), text, buttons, 0);
    }
}
