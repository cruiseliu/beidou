package org.gms.remote.modules.quest;

import org.gms.client.quest.Quest;
import org.gms.remote.AbstractModule;
import org.gms.remote.modules.quest.server.QuestCompleteEvent;
import org.gms.remote.modules.quest.server.QuestExpiredEvent;
import org.gms.remote.modules.quest.server.QuestForfeitedEvent;
import org.gms.remote.modules.quest.server.QuestSeriesCompleteEvent;
import org.gms.remote.modules.quest.server.QuestStartEvent;
import org.gms.remote.modules.quest.server.QuestStateEvent;
import org.gms.remote.modules.quest.server.QuestTimeLimitEvent;
import org.gms.remote.modules.quest.server.QuestTimeLimitRemovedEvent;

import java.util.Map;

/**
 * 语义模块：任务域。C→S 收侧为玩家任务意图入口（Handler）；S→C 为任务状态帧
 * （SHOW_STATUS_INFO quest 体 + UPDATE_QUEST_INFO 各分支）——调用方传基础类型
 * 事实（questId/status 值/进度表/时间）或语义实体（接取/完成全量通知），wire 形态归版本实现。
 */
public abstract class QuestModule extends AbstractModule {

    // ── S→C：任务状态帧 ──

    /** 任务状态/进度更新（status = QuestStatus 枚举值；infoNumber 关联任务同步也走本入口）。 */
    public final void updateQuestState(int questId, int status, Map<Integer, String> progress) {
        post(new QuestStateEvent(questId, status, progress));
    }

    /**
     * 任务接取全量通知：主任务状态帧 + infoNumber 关联任务状态同步（quest.getInfo()）
     * + NPC 交付确认，由版本实现按序发多帧。实体档——帧物化归版本统一冻结门。
     */
    public final void questStarted(Quest quest) {
        post(new QuestStartEvent(quest));
    }

    /**
     * 任务完成全量通知：完成状态帧 + 完成演出帧（仅本人；全图演出归地图广播），
     * 由版本实现按序发多帧。实体档——帧物化归版本统一冻结门。
     */
    public final void questComplete(Quest quest) {
        post(new QuestCompleteEvent(quest));
    }

    /** 任务放弃。 */
    public final void questForfeited(int questId) {
        post(new QuestForfeitedEvent(questId));
    }

    /** 任务系列终结标记（任务链无下一环时的收尾；wire = UPDATE_QUEST_INFO type 8 交付分支）。 */
    public final void questSeriesComplete(int questId, int npc) {
        post(new QuestSeriesCompleteEvent(questId, npc));
    }

    /** 任务到期作废。 */
    public final void questExpired(int questId) {
        post(new QuestExpiredEvent(questId));
    }

    /** 任务限时追加（remainingMillis = 距到期剩余毫秒）。 */
    public final void addQuestTimeLimit(int questId, long remainingMillis) {
        post(new QuestTimeLimitEvent(questId, remainingMillis));
    }

    /** 任务限时移除。 */
    public final void removeQuestTimeLimit(int questId) {
        post(new QuestTimeLimitRemovedEvent(questId));
    }

    // ── C→S：玩家任务意图 ──

    /** 接取任务意图入口（player actor strand 上执行；条件校验与脚本分流归 gameplay）。 */
    public interface Handler {
        void startQuest(int questId, int npc);

        void completeQuest(int questId, int npc, Integer selection);

        void forfeitQuest(int questId);

        void restoreLostItem(int questId, int itemId);

        void startScriptedQuest(int questId, int npc);

        void endScriptedQuest(int questId, int npc);
    }
}
