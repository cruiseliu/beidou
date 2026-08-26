package org.gms.model.json;

import java.util.List;

/**
 * ItemSlot（背包物品）的持久化数据载体（character_json 的 inventory 域，按背包类型分列表）。
 * 默认值序列化为 null（fastjson2 省略）：flag=0、owner=""、expiration=-1、petId=-1。
 * cashInfo 不序列化——反序列化时由 ItemSlot 构造按 ii.isCash(id) 重建；
 * 装备物品嵌 {@link EquipmentData}（非装备为 null）。
 */
public class ItemData {
    public int itemId;
    public int position;
    public int quantity;
    public Integer flag;
    public String owner;
    /** -1（永久）序列化为 null */
    public Long expiration;
    /** -1（非宠物）序列化为 null */
    public Integer petId;
    public EquipmentData equip;
}
