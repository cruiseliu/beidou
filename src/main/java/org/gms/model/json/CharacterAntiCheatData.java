package org.gms.model.json;

/**
 * CharacterAntiCheat 的持久化数据载体（character_json 的 antiCheat 域）。
 * 纯 public 字段，由 fastjson2 序列化/反序列化。
 */
public class CharacterAntiCheatData {
    /** 监狱刑期到期时间戳（ms；-1 = 从未入狱） */
    public long jailExpiration;
}
