package org.gms.client.scripting.api;

import org.gms.client.Player;
import org.gms.remote.modules.npc.client.DialogButtons;

/**
 * 脚本 API 白名单——对话页面（send* 族）。会话无绑定：npc id 由脚本逐调用提供
 * （官方旧脚本本就以脚本知识持有自己的 npc），本类不登记会话、不持身份——任务会话
 * 身份与生命周期仍归 {@link org.gms.client.scripting.QuestApi_OLD}。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败）。self 流经 remote
 * npc 模块编码直发本连接（样式字节契约见 NpcModule）；wire 禁令合规——脚本不触达
 * PacketCreator（doc/script-engine.md §4.7）。
 */
public final class TalkApi {

    /** 单按钮"下一步"（00 01，gms083 实测） */
    public void sendNext(int npc, String text) {
        Player.require("TalkApi.sendNext").character().getRemote().npc().talk(npc, text, DialogButtons.NEXT, 0);
    }

    /** 单按钮"上一步"（01 00，gms083 实测）；按钮点击即整链收尾（原版 sendPrev 页语义） */
    public void sendPrevOk(int npc, String text) {
        Player.require("TalkApi.sendPrevOk").character().getRemote().npc().talk(npc, text, DialogButtons.PREV_OK, 0);
    }

    /** 双按钮"上一步/下一步"（01 01，gms083 实测） */
    public void sendPrevNext(int npc, String text) {
        Player.require("TalkApi.sendPrevNext").character().getRemote().npc().talk(npc, text, DialogButtons.PREV_NEXT, 0);
    }

    /** 单按钮"确定" */
    public void sendOk(int npc, String text) {
        Player.require("TalkApi.sendOk").character().getRemote().npc().talk(npc, text, DialogButtons.OK, 0);
    }

    /** 是/否（mode 1 = 是，0 = 否） */
    public void sendYesNo(int npc, String text) {
        Player.require("TalkApi.sendYesNo").character().getRemote().npc().talk(npc, text, DialogButtons.YES_NO, 0);
    }

    /** 接受/拒绝（mode 1 = 接受，0 = 拒绝） */
    public void sendAcceptDecline(int npc, String text) {
        Player.require("TalkApi.sendAcceptDecline").character().getRemote().npc().talk(npc, text, DialogButtons.ACCEPT_DECLINE, 0);
    }
}
