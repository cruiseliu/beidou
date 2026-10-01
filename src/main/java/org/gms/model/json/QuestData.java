package org.gms.model.json;

import java.util.List;
import java.util.Map;

/**
 * 单个任务的持久化载荷（character_json 的 quests 域逐项条目，仿 {@link PetData}；
 * 字段映射在实体侧 Quest.toData / 恢复构造）。
 * completionTime 保存 ms 原值；空集合用 null（无进度/无探索图不落库）。
 * 纯 public 字段，由 fastjson2 序列化/反序列化。
 */
public class QuestData {
    /** 任务 id（QuestWz.getId()） */
    public int quest;
    /** 状态（QuestStatus 枚举值） */
    public int status;
    /** 完成时刻（ms；未完成时透传实体默认值） */
    public long completionTime;
    /** 任务进度（progressId → 进度串，mob 计数等；null = 无进度） */
    public Map<Integer, String> progress;
    /** 勋章任务探索图（null = 无） */
    public List<Integer> medalMaps;
}
