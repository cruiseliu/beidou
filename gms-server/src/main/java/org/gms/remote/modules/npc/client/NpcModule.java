package org.gms.remote.modules.npc.client;

/**
 * 语义模块：NPC 对话域（对话页 self 流）。
 *
 * <p>按钮布局语义见 {@link DialogButtons}；版本实现的 wire 映射：NEXT=对话页(0)+「00 01」、
 * PREV_OK=对话页(0)+「01 00」、PREV_NEXT=对话页(0)+「01 01」、OK=对话页(0)+「00 00」、
 * YES_NO=1（无尾字节）、ACCEPT_DECLINE=0x0C（无尾字节）。
 */
public interface NpcModule {

    /** NPC 对话页（self 流）：按 buttons 语义编码并发送给本连接。 */
    void talk(int npc, String text, DialogButtons buttons, int speaker);

    /** 过场 UI 图（借 item-inchat 帧发 UI 路径；动作锁解除归 basic().unlockActions）。 */
    void showInfo(String path);

    /** SERVERMESSAGE 通知（self 流；serverNotice(type, message) 形态）。 */
    void dropMessage(int type, String message);
}
