package org.gms.remote.modules.quest;

import org.gms.remote.AbstractModule;

/**
 * 语义模块：任务域。当前只有 C→S 收侧（玩家任务意图入口）；S→C 预留（任务状态变更
 * 目前随 stats/inventory 域的 STAT_CHANGED / INVENTORY_OPERATION 搭载，无独立包型）。
 */
public abstract class QuestModule extends AbstractModule {

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
