package org.gms.model.json;

import java.util.Map;

/**
 * CharacterSp 的持久化数据载体（character_json 的 sp 域）。
 * remainingSp：jobId → 该职业槽剩余 SP（新手槽 jobId=0 与职业技能点槽分立）。
 * 旧格式（int[2]：下标 0=新手、1=其他职业）已随 SP 按职业分桶存储而废弃。
 */
public class CharacterSpData {
    public Map<Integer, Integer> remainingSp;
}
