package org.gms.model.json;

import java.util.List;

/**
 * CharacterQuests 的持久化数据载体（character_json 的 quests 域）——只管理容器。
 * 任务状态权威 = 本域（queststatus/questprogress/medalmaps SQL 表已随 V0.1.4 下线）；
 * 逐项载荷见 {@link QuestData}（顺序 = quests 表插入序，恢复时保持）。
 */
public class CharacterQuestsData {
    /** null = 无任务（不落库） */
    public List<QuestData> quests;
}
