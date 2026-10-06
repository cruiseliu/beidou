package org.gms.client.scripting.api;

import org.gms.client.Player;

/**
 * 脚本 API 白名单——任务状态面（forceStartQuest/forceCompleteQuest）。会话无绑定：
 * questId/npcId 由脚本逐调用提供（与 TalkApi 同哲学；npcId 为任务引导/对话归属 npc，
 * 进 Quest.forceStart/forceComplete 的 npc 参数）。状态推进跳过常规 requirement 检查
 * ——前置条件与演出归脚本剧情负责（原 player_old.forceStartQuest 脚本路径的收编，
 * AbstractPlayerInteraction.startQuest 实现归位后的脚本入口）。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败）。<b>无状态</b>。
 */
public final class QuestApi {

    /** 强制接取（任务状态 → STARTED）；返回 Quest.forceStart 结果。 */
    public boolean forceStartQuest(int questId, int npcId) {
        return Player.require("QuestApi.forceStartQuest").character().forceStartQuest(questId, npcId);
    }

    /** 强制完成（任务状态 → COMPLETED）；返回 Quest.forceComplete 结果。 */
    public boolean forceCompleteQuest(int questId, int npcId) {
        return Player.require("QuestApi.forceCompleteQuest").character().forceCompleteQuest(questId, npcId);
    }
}
