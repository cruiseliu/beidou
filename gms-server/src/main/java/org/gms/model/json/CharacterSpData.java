package org.gms.model.json;

/**
 * CharacterSp 的持久化数据载体（character_json 的 sp 域）。
 * remainingSp 下标 0=新手、1=其他职业；后续改为动态长度（= 转职次数）。
 */
public class CharacterSpData {
    public int[] remainingSp;
}
