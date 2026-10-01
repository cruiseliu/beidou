package org.gms.model.json;

import java.util.List;

/**
 * CharacterPets 的持久化数据载体（character_json 的 pets 域）。纯 public 字段，由 fastjson2 序列化/反序列化。
 * <ul>
 *   <li>summoned：召唤中的宠物 id（槽位序）——恢复召唤状态由域内负责；</li>
 *   <li>ignoreItems：拾取过滤共享列表（全角色宠物共用）。</li>
 * </ul>
 */
public class CharacterPetsData {
    public List<Integer> summoned;
    public List<Integer> ignoreItems;
}
