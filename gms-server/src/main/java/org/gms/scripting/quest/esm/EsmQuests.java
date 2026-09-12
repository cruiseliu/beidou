package org.gms.scripting.quest.esm;

import org.gms.client.character.Character;
import org.gms.scripting.JsModule;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ESM 任务脚本接入点（doc/13）：按脚本文件存在性分流——
 * <ul>
 *   <li>{@code scripts/quest/esm/<id>.mjs} 存在 → ESM 新系统（player actor context，
 *       NextLevel 之前的重放模型：模块级 status 状态机 + more 重入）；</li>
 *   <li>不存在 → 旧 QuestScriptManager 路径（并行共存，互不影响）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：全部方法须在 player strand 上调用（调用方 handler 已 queued）；
 * runner.call/moduleFor 保证 polyglot Context 的串行进入。
 */
public final class EsmQuests {

    private EsmQuests() {
    }

    public static String scriptPath(int questId) {
        return "quest/esm/" + questId + ".mjs";
    }

    /** ESM 脚本是否存在（分流探测；文件级判断，不触发 eval）。 */
    public static boolean exists(int questId) {
        return Files.exists(Path.of("scripts", scriptPath(questId)));
    }

    /**
     * 脚本化接取（QUEST_ACTION action=4 的 ESM 分支）：建会话，按 WZ startscript 指定名首入。
     * WZ 未声明入口名（非脚本任务）不应到达此处——由调用方的分流保证。
     */
    public static void start(Character chr, int questId, int npc, String entry) {
        QuestApi api = new QuestApi(chr, questId, npc, entry);
        chr.setEsmQuest(api);
        invoke(api, entry, 1, 0, 0);
    }

    /** 脚本化完成（QUEST_ACTION action=5 的 ESM 分支）：建会话，按 WZ endscript 指定名首入。 */
    public static void end(Character chr, int questId, int npc, String entry) {
        QuestApi api = new QuestApi(chr, questId, npc, entry);
        chr.setEsmQuest(api);
        invoke(api, entry, 1, 0, 0);
    }

    /** 对话重入（NPC_TALK_MORE 的 ESM 分支）：以会话登记的重入函数名继续状态机。 */
    public static void more(Character chr, byte mode, byte type, int selection) {
        QuestApi api = chr.esmQuest();
        if (api == null) {
            return;
        }
        invoke(api, api.entry(), mode, type, selection);
    }

    private static void invoke(QuestApi api, String entry, int mode, int type, int selection) {
        Character chr = api.owner();
        chr.getScriptRunner().call(() -> {
            JsModule module = chr.getScriptRunner().moduleFor(scriptPath(api.questId()));
            return module.call(entry, mode, type, selection, api);
        });
    }
}
