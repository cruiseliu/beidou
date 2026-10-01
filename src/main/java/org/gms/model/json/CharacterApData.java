package org.gms.model.json;

/**
 * CharacterAp 的持久化数据载体（character_json 的 ap 域）。纯 public 字段，由 fastjson2 序列化/反序列化。
 */
public class CharacterApData {
    public int remainingAp;
    public int hpMpApUsed;
}
