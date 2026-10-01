package org.gms.model.json;

import com.alibaba.fastjson2.JSON;

/**
 * CharacterStats 的持久化数据载体。纯 public 字段，由 fastjson2 序列化/反序列化。
 * CharacterStats 通过 toData() / applyData() 与此类互转，不感知序列化格式。
 */
public class CharacterStatsData {
    public int str, dex, int_, luk;
    public int hp, mp, maxHp, maxMp;

    public String serialize() {
        return JSON.toJSONString(this);
    }

    public static CharacterStatsData deserialize(String json) {
        return JSON.parseObject(json, CharacterStatsData.class);
    }
}
