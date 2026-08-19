package org.gms.model.json;

import com.alibaba.fastjson2.JSON;

/**
 * character_json.data 的顶层载体：一个角色一条 JSON，各数据域分节存放，便于扩展
 * （如将来加入 CharacterSkillsData skills 等域）。纯 public 字段，由 fastjson2 序列化/反序列化。
 * timestamp：本信封的保存时刻（ms），用于跨域恢复时间敏感字段（如 debuffs 的 startTime 推移）。
 */
public class CharacterData {
    public CharacterStatsData stats;
    public CharacterSkillsData skills;
    public CharacterApData ap;
    public CharacterSpData sp;
    public CharacterDebuffsData debuffs;
    /** 职业 id（CharacterJob.job），与 character 表 job 列冗余双写 */
    public int jobId;
    /** 当前地图 id（CharacterMap.mapId），与 character 表 map 列冗余双写 */
    public int mapId;
    public long timestamp;

    public CharacterData() {
    }

    public CharacterData(CharacterStatsData stats) {
        this.stats = stats;
    }

    public String serialize() {
        return JSON.toJSONString(this);
    }

    public static CharacterData deserialize(String json) {
        return JSON.parseObject(json, CharacterData.class);
    }
}
