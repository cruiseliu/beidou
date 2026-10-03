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
}
