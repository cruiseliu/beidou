package org.gms.remote.modules.npc;

import org.gms.remote.AbstractModule;
import org.gms.remote.modules.npc.client.DialogButtons;
import org.gms.remote.modules.npc.server.NpcTalkEvent;
import org.gms.remote.modules.npc.server.ServerNoticeEvent;
import org.gms.remote.modules.npc.server.ShowInfoEvent;

/**
 * 语义模块：NPC 对话域（对话页 self 流）。
 *
 * <p>按钮布局语义见 {@link DialogButtons}；版本实现的 wire 映射：NEXT=对话页(0)+「00 01」、
 * PREV_OK=对话页(0)+「01 00」、PREV_NEXT=对话页(0)+「01 01」、OK=对话页(0)+「00 00」、
 * YES_NO=1（无尾字节）、ACCEPT_DECLINE=0x0C（无尾字节）。
 */
public abstract class NpcModule extends AbstractModule {

    /** NPC 对话页（self 流）：按 buttons 语义编码并发送给本连接。 */
    public final void talk(int npc, String text, DialogButtons buttons, int speaker) {
        post(new NpcTalkEvent(npc, text, buttons, speaker));
    }

    /** 过场 UI 图（借 item-inchat 帧发 UI 路径；动作锁解除归 basic().unlockActions）。 */
    public final void showInfo(String path) {
        post(new ShowInfoEvent(path));
    }

    /** SERVERMESSAGE 通知（self 流；serverNotice(type, message) 形态）。 */
    public final void dropMessage(int type, String message) {
        post(new ServerNoticeEvent(type, message));
    }

    // ── C→S：对话续行意图 ──

    /**
     * 对话续行意图入口（player actor strand 上执行；ESM 会话 / 任务脚本 / NPC 脚本的
     * 重入分流归 gameplay）。
     *
     * @param lastMsg   客户端回显的上一页对话类型（脚本续行路由依据）
     * @param action    0 = 结束对话，1 = 继续
     * @param text      文本输入页的输入内容（非文本页 null）
     * @param selection 选择项（无选择 -1；文本页恒 -1）
     */
    public interface Handler {
        void talkMore(int lastMsg, int action, String text, int selection);
    }
}
