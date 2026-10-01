package org.gms.remote.gms083.server.translators;

import org.gms.remote.gms083.server.packets.NPCTalkPacket;
import org.gms.remote.modules.npc.client.DialogButtons;

/**
 * NPC 对话域翻译：DialogButtons 语义 → NPC_TALK wire 的 msgType/包尾按钮字节。
 * 语义查表归翻译层（package-client.md §6，route 对参数不做理解只透传 §5）。
 */
public final class NpcTranslator {

    private static final int MSG_TALK = 0;
    private static final int MSG_YES_NO = 1;
    private static final int MSG_ACCEPT_DECLINE = 0x0C;

    /** 对话页编码（route 直发本连接，不入合并冲刷）。 */
    public NPCTalkPacket talk(int npc, String text, DialogButtons buttons, int speaker) {
        int msgType;
        int[] endBytes;
        switch (buttons) {
            case NEXT -> {
                msgType = MSG_TALK;
                endBytes = new int[]{0, 1};
            }
            case PREV_OK -> {
                msgType = MSG_TALK;
                endBytes = new int[]{1, 0};
            }
            case PREV_NEXT -> {
                msgType = MSG_TALK;
                endBytes = new int[]{1, 1};
            }
            case OK -> {
                msgType = MSG_TALK;
                endBytes = new int[]{0, 0};
            }
            case YES_NO -> {
                msgType = MSG_YES_NO;
                endBytes = new int[0];
            }
            case ACCEPT_DECLINE -> {
                msgType = MSG_ACCEPT_DECLINE;
                endBytes = new int[0];
            }
            default -> throw new IllegalArgumentException("未支持的对话框按钮配置: " + buttons);
        }
        return new NPCTalkPacket(npc, msgType, speaker, text, endBytes);
    }
}
