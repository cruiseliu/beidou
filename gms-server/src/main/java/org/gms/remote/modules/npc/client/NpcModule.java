package org.gms.remote.modules.npc.client;

/**
 * 语义模块：NPC 对话域（对话页 self 流）。
 *
 * <p>msgType 即 NPC_TALK wire 的对话类型字节（客户端回包的 lastMsg 回显它）：
 * 0=对话页、1=Yes/No、0x0C=接受/拒绝；endBytes 为包尾按钮配置字节
 * （如 sendNext=「00 01」、sendPrev=「01 00」、sendNextPrev=「01 01」、sendOk=「00 00」、
 * yesno/acceptdecline 无尾字节）。
 */
public interface NpcModule {

    int MSG_TALK = 0;
    int MSG_YES_NO = 1;
    int MSG_ACCEPT_DECLINE = 0x0C;

    /** NPC 对话页（self 流）：按 msgType/endBytes 编码并发送给本连接。 */
    void talk(int npc, int msgType, int speaker, String text, int... endBytes);

    /** 过场 UI 图（借 item-inchat 帧发 UI 路径；动作锁解除归 basic().unlockActions）。 */
    void showInfo(String path);

    /** SERVERMESSAGE 通知（self 流；serverNotice(type, message) 形态）。 */
    void dropMessage(int type, String message);
}
