package org.gms.remote.modules.npc.server;

import org.gms.remote.ServerEvent;
import org.gms.remote.modules.npc.client.DialogButtons;

/** NPC 对话页（self 流；buttons 语义 → wire 映射归翻译层）。 */
public record NpcTalkEvent(int npc, String text, DialogButtons buttons, int speaker) implements ServerEvent {
}
