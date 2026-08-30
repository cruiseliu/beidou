package org.gms.model.json;

import com.alibaba.fastjson2.JSON;

/**
 * Pet 的持久化数据载体（pets_json.data）。纯 public 字段，由 fastjson2 序列化/反序列化。
 * Pet 通过 toData() / applyData() 与此类互转，不感知序列化格式。
 * 生命周期字段（expiresAt/active）语义见 doc/11 §4；petid 是表主键，不在 data 内。
 */
public class PetData {
    public String name;
    public byte level;
    /** 亲密度 */
    public int tameness;
    public int fullness;
    public boolean summoned;
    /** 属性旗标（PetAttribute 位集） */
    public int flag;
    /** 到期 epoch ms（-1 = 永久） */
    public long expiresAt;
    /** 活跃态（到期失活 = false） */
    public boolean active;

    public String serialize() {
        return JSON.toJSONString(this);
    }

    public static PetData deserialize(String json) {
        return JSON.parseObject(json, PetData.class);
    }
}
