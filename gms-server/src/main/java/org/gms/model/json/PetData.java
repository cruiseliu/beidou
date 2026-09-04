package org.gms.model.json;

import java.util.List;

import com.alibaba.fastjson2.JSON;

public class PetData {
    public int petId;
    public int itemId;
    public String name;
    public int tameness;
    public int level;
    public int fullness;
    public boolean summoned;
    public int flags;
    public long expiration;
    public boolean alive;
    public boolean expiredOffline;
    public List<Integer> ignoreItems;

    public String serialize() {
        return JSON.toJSONString(this);
    }

    public static PetData deserialize(String json) {
        return JSON.parseObject(json, PetData.class);
    }
}
