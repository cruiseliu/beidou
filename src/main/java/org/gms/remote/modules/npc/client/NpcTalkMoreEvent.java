package org.gms.remote.modules.npc.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 对话续行意图（NPC_TALK_MORE）：对话页按钮/选择项/文本输入的统一应答。
 * 分流（ESM 会话 / 任务脚本 / NPC 脚本重入）归 gameplay；字段即客户端回显事实。
 *
 * @param lastMsg   上一页对话类型（脚本续行路由依据）
 * @param action    0 = 结束对话，1 = 继续
 * @param text      文本输入页的输入内容（非文本页 null）
 * @param selection 选择项（无选择 -1；文本页恒 -1）
 */
public record NpcTalkMoreEvent(int lastMsg, int action, String text, int selection) implements ClientEvent {

    @Override
    public Module module() {
        return Module.NPC;
    }
}
