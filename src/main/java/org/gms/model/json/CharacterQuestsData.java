package org.gms.model.json;

import java.util.List;
import java.util.Map;

/**
 * CharacterQuests 的持久化数据载体（character_json 的 quests 域）。
 * 任务状态权威 = 本域（queststatus/questprogress/medalmaps SQL 表已随 V0.1.4 下线）。
 * completionTime/expirationTime 保存 ms 原值（旧 SQL 的秒级截断不再发生）；
 * 空集合用 null（无进度/无任务不落库）。纯 public 字段，由 fastjson2 序列化/反序列化。
 */
public class CharacterQuestsData {
    /** null = 无任务；否则逐项保存（顺序 = quests 表插入序，恢复时保持） */
    public List<QuestEntryData> quests;

    public static class QuestEntryData {
        /** 任务 id（QuestWz.getId()） */
        public int quest;
        /** 状态（QuestStatus.Status.getId()） */
        public int status;
        /** 完成时刻（ms；未完成时与 QuestStatus 默认值一致透传） */
        public long completionTime;
        /** 限时到期时刻（ms；0 = 无限时） */
        public long expirationTime;
        /** 放弃次数 */
        public int forfeited;
        /** 完成次数 */
        public int completed;
        /** 任务进度（progressId → 进度串，mob 计数等；null = 无进度） */
        public Map<Integer, String> progress;
        /** 勋章任务探索图（null = 无） */
        public List<Integer> medalMaps;
    }
}
