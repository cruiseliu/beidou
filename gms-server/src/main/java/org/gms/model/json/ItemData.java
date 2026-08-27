package org.gms.model.json;

import java.util.List;

/**
 * ItemSlot（背包物品）的持久化数据载体（character_json 的 inventory 域，按背包类型分列表）。
 * 默认值序列化为 null（fastjson2 省略）：itemFlags 空、owner=""、expiration=-1、petId=-1、charge=0。
 * cashInfo 不序列化——反序列化时由构造按 ii.isCash(id) 重建；
 * 装备物品嵌 {@link EquipmentData}（非装备为 null）。
 */
public class ItemData {
    public int itemId;
    public int position;
    public int quantity;
    /** 类型化实例旗标（{@link org.gms.client.inventory.ItemFlag} 名单；恒定标签 SCISSOR_USABLE 也持久化，
     *  恢复时会被构造期落位重算覆盖——一致性无损）；旧档案仅有下方 flag 位视图 */
    public List<String> itemFlags;
    /** 可充值物品的可使用次数（非可充值 / 为 0 时省略）；quantity 对可充值语义为组数（恒 1） */
    public Integer charge;
    public String owner;
    /** -1（永久）序列化为 null */
    public Long expiration;
    /** -1（非宠物）序列化为 null */
    public Integer petId;
    public EquipmentData equip;
}
