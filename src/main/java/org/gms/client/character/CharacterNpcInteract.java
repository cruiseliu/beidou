package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.scripting.QuestApi_OLD;
import org.gms.client.scripting.QuestScript;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.modules.npc.NpcModule;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestScriptManager;

/**
 * NPC 对话交互组件（npc 域 C→S 状态收拢）：对话续行分流（原 NPCMoreTalkHandler 语义体）
 * + ESM 任务脚本会话槽位——原散在 Character 的 talkMore 方法体与 esmQuest 槽移入本组件，
 * 会话状态与分流逻辑同址。
 *
 * <p><b>线程模型</b>：talkMore 由收包插座直调（NpcInbound → Handler 槽位，player actor
 * strand 上执行）；esmQuest 槽 volatile，写点（QuestScript.start/end 首入）与读点
 * （talkMore 分流）同在 player strand，volatile 只为登出清场竞态兜底。
 */
public final class CharacterNpcInteract implements NpcModule.Handler {

    private final Character owner;

    /** 活跃 ESM 任务脚本会话；无对话时 null（doc/13 §15） */
    private volatile QuestApi_OLD esmQuest;

    CharacterNpcInteract(Character owner) {
        this.owner = owner;
    }

    /** 接插收包 Handler 槽位（入场绑定，doc/12） */
    public void bindClientHandlers(ClientEventHandlerRegistry registry) {
        registry.registerNpc(this);
    }

    @Override
    public void talkMore(int lastMsg, int action, String text, int selection) {
        // ESM 会话分流（doc/13 §15）：活跃 ESM 任务会话 → 重入其状态机（文本输入变体
        // 未支持，1021 不涉及；mode=-1 由脚本首分支 dispose）。旧路径原样跟随。
        if (esmQuest != null) {
            if (lastMsg == 2 && action == 0) {
                esmQuest.dispose();
            } else if (lastMsg != 2) {
                QuestScript.more(owner, (byte) action, (byte) lastMsg, selection);
            }
            return;
        }
        // lastMsg 等于 2 为文本输入页（有 returnText），否则为选择/按钮页
        Client c = owner.getClient();
        if (lastMsg == 2) {
            if (action != 0) {
                if (c.getQM() != null) {
                    c.getQM().setGetText(text);
                    if (c.getQM().isStart()) {
                        QuestScriptManager.getInstance().start(c, (byte) action, (byte) lastMsg, -1);
                    } else {
                        QuestScriptManager.getInstance().end(c, (byte) action, (byte) lastMsg, -1);
                    }
                } else {
                    c.getCM().setGetText(text);
                    npcScriptRouting(c, (byte) action, (byte) lastMsg, -1);
                }
            } else if (c.getQM() != null) {
                c.getQM().dispose();
            } else {
                c.getCM().dispose();
            }
        } else {
            if (c.getQM() != null) {
                if (c.getQM().isStart()) {
                    QuestScriptManager.getInstance().start(c, (byte) action, (byte) lastMsg, selection);
                } else {
                    QuestScriptManager.getInstance().end(c, (byte) action, (byte) lastMsg, selection);
                }
            } else {
                npcScriptRouting(c, (byte) action, (byte) lastMsg, selection);
            }
        }
    }

    private void npcScriptRouting(Client c, byte action, byte lastMsg, int selection) {
        if (c.getCM().getNextLevelContext().getLevelType() == null) {
            NPCScriptManager.getInstance().action(c, action, lastMsg, selection);
        } else {
            NPCScriptManager.getInstance().nextLevel(c, action, lastMsg, selection);
        }
    }

    // ── ESM 会话槽位 ──

    public QuestApi_OLD esmQuest() { return esmQuest; }

    public void setEsmQuest(QuestApi_OLD session) { this.esmQuest = session; }

    public void clearEsmQuest(QuestApi_OLD session) {
        if (esmQuest == session) {
            esmQuest = null;
        }
    }
}
