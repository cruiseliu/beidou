package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.scripting.InteractContext;
import org.gms.client.scripting.QuestScript;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.modules.npc.NpcModule;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestScriptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NPC 对话交互组件（npc 域 C→S 状态收拢）：对话续行分流（原 NPCMoreTalkHandler 语义体）
 * + 脚本对话上下文（InteractContext）的全生命周期管理——创建（beginContext）/登记/查找
 * （talkMore 分流）/清除（clearContext）同址，QuestScript 只经方法参数接收上下文。
 *
 * <p><b>线程模型</b>：talkMore 由收包插座直调（NpcInbound → Handler 槽位，player actor
 * strand 上执行）；context 槽 volatile，写点（start/end 首入登记）与读点（talkMore
 * 分流）同在 player strand，volatile 只为登出清场竞态兜底。
 */
public final class CharacterNpcInteract implements NpcModule.Handler {

    private static final Logger log = LoggerFactory.getLogger(CharacterNpcInteract.class);

    private final Character owner;

    /** 活跃脚本对话上下文；无对话时 null（doc/13 §15） */
    private volatile InteractContext context;

    CharacterNpcInteract(Character owner) {
        this.owner = owner;
    }

    /** 接插收包 Handler 槽位（入场绑定，doc/12） */
    public void bindClientHandlers(ClientEventHandlerRegistry registry) {
        registry.registerNpc(this);
    }

    /**
     * 创建并登记脚本对话上下文（首入入口：QuestScript.start/end 调用）。
     * 返回登记后的实例——调用方仅作参数向后传递，不另存（槽位唯一真源在本组件）。
     * 已有活跃上下文时照常覆盖（last-write-wins），但 log error——上一会话未走
     * dispose 即被替换属异常流（对话中途再开新对话），须在线上排查。
     */
    public InteractContext beginContext(int npcId, String entry, String scriptPath) {
        InteractContext prev = context;
        if (prev != null) {
            log.error("beginContext 覆盖未清理的活跃对话上下文（旧 npc={} / 新 npc={} entry={}）——上一会话未 dispose",
                    prev.getNpcId(), npcId, entry);
        }
        InteractContext ctx = new InteractContext(owner, npcId, scriptPath, entry);
        this.context = ctx;
        return ctx;
    }

    /** 清除会话登记（InteractContext.dispose 回调；幂等——仅当仍登记同一实例时生效） */
    public void clearContext(InteractContext ctx) {
        if (context == ctx) {
            context = null;
        }
    }

    @Override
    public void talkMore(int lastMsg, int action, String text, int selection) {
        // 脚本会话分流（doc/13 §15）：活跃对话上下文 → 重入其状态机（文本输入变体
        // 未支持，1021 不涉及；mode=-1 由脚本首分支 dispose）。旧路径原样跟随。
        InteractContext ctx = context;
        if (ctx != null) {
            if (lastMsg == 2 && action == 0) {
                ctx.dispose();
            } else if (lastMsg != 2) {
                QuestScript.more(ctx, action, lastMsg, selection);
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
}
