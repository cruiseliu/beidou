package org.gms.client.scripting;

import org.gms.client.character.Character;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ESM 任务脚本接入点（doc/13）：按脚本文件存在性分流——
 * <ul>
 *   <li>{@code actorscripts/quest/<id>.js} 存在 → ESM 新系统（player actor context，
 *       NextLevel 之前的重放模型：模块级 status 状态机 + more 重入）；</li>
 *   <li>不存在 → 旧 QuestScriptManager 路径（并行共存，互不影响）。</li>
 * </ul>
 *
 * <p><b>归置与线程模型</b>：本类属于 <b>player strand</b> 域——宿主是 per 角色的
 * CharacterScriptRunner（polyglot Context 归角色所有），全部方法须在 owning player
 * strand 上调用（调用方 handler 已 queued）；runner.call/moduleFor 保证 Context 的
 * 串行进入。归入 {@code client.scripting} 以区别于 {@code org.gms.scripting} 下的
 * 全局 legacy 脚本管理器（AbstractScriptManager 一族）。
 */
public final class QuestScript {

    private QuestScript() {
    }

    public static String scriptPath(int questId) {
        return "quest/" + questId + ".js";
    }

    /** ESM 脚本是否存在（分流探测；文件级判断，不触发 eval）。 */
    public static boolean exists(int questId) {
        return Files.exists(Path.of("actorscripts", scriptPath(questId)));
    }

    /**
     * 脚本化接取（QUEST_ACTION action=4 的 ESM 分支）：建上下文（经
     * CharacterNpcInteract.beginContext 创建并登记会话槽），按 WZ startscript 指定名首入。
     * WZ 未声明入口名（非脚本任务）不应到达此处——由调用方的分流保证。
     */
    public static void start(Character chr, int questId, int npc, String entry) {
        InteractContext ctx = chr.getNpcInteract().beginContext(npc, entry, scriptPath(questId));
        ctx.call(questId, 1, 0, 0);
    }

    /** 脚本化完成（QUEST_ACTION action=5 的 ESM 分支）：建上下文，按 WZ endscript 指定名首入。 */
    public static void end(Character chr, int questId, int npc, String entry) {
        InteractContext ctx = chr.getNpcInteract().beginContext(npc, entry, scriptPath(questId));
        ctx.call(questId, 1, 0, 0);
    }

    /**
     * 对话重入（NPC_TALK_MORE 的 ESM 分支）：以登记上下文的重入函数名继续状态机。
     * context 由 CharacterNpcInteract.talkMore 从会话槽取出传入（非空保证在调用方）。
     */
    public static void more(InteractContext ctx, int mode, int type, int selection) {
        // questId 占位 -1：重入恒走 interaction.js 的 #dispatch（会话身份相同），questId
        // 不可达消费；其唯一消费者 #begin 仅在首入发生，而首入只来自 start/end（带真值）。
        // 热重载已停用（CharacterScriptRunner），不存在"#ctx 归零后重入误入 #begin"的路径。
        ctx.call(-1, mode, type, selection);
    }
}
