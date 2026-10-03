package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * NPC_TALK_MORE 收包 codec（v83）：对话续行回包——对话页按钮/选择项/文本输入的统一应答。
 * 本类只做解码；lastMsg/action 之后的载荷形态由上一页对话类型决定：
 * lastMsg==2（文本输入页）action!=0 → 输入文本（readString）；lastMsg==2 action==0 → 无载荷（结束对话）；
 * 其他页 → 可选选择项——remaining&gt;=4 读 int、remaining&gt;0 读 unsigned byte、否则无（selection=-1，
 * 短包怪癖原样保留）。数据的用途（脚本重入分流）不归本类。
 */
public final class NPCTalkMorePacket {

    private NPCTalkMorePacket() {
    }

    /**
     * 解码产物：字段即客户端回显事实。
     *
     * @param lastMsg   上一页对话类型
     * @param action    0 = 结束对话，1 = 继续
     * @param text      文本输入页的输入内容（非文本页 null）
     * @param selection 选择项（无选择 -1；文本页恒 -1）
     */
    public record TalkMore(int lastMsg, int action, String text, int selection) {
    }

    public static TalkMore decode(ByteBufReader p) {
        int lastMsg = p.readByte();
        int action = p.readByte();
        if (lastMsg == 2) {
            if (action != 0) {
                return new TalkMore(lastMsg, action, p.readString(), -1);
            }
            return new TalkMore(lastMsg, action, null, -1);   // 结束对话，无载荷
        }
        int selection = -1;
        if (p.available() >= 4) {
            selection = p.readInt();
        } else if (p.available() > 0) {
            selection = p.readUnsignedByte();
        }
        return new TalkMore(lastMsg, action, null, selection);
    }
}
