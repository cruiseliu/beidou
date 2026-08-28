package org.gms.remote;

import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.Pet;

/**
 * 背包槽位变更的语义记录（不可变）：{@link InventoryModule#updateInventory} 的书写单位，
 * 与 SemanticEvent 同构的 interface/record 形式。数量为绝对终值；
 * 翻译成具体封包（含 ItemSlot 快照拷贝与可充值 charge→quantity 还原）是版本实现的私事。
 */
public sealed interface SlotChange {

    /** 新槽入包（pet 仅宠物物品携带，非宠物为 null） */
    record Added(ItemSlot item, Pet pet) implements SlotChange {
        public Added(ItemSlot item) {
            this(item, null);
        }
    }

    /** 槽数量更新（绝对值；可充值=可使用次数） */
    record QuantityUpdated(ItemSlot item) implements SlotChange {}

    /** 槽位移动（换装/排序类操作） */
    record Moved(ItemSlot item, short oldPosition) implements SlotChange {}

    /** 槽移除 */
    record Removed(ItemSlot item) implements SlotChange {}
}
