package org.gms.remote;

import org.gms.client.inventory.Item;
import org.gms.client.pet.Pet;

/**
 * 背包槽位变更的语义记录：身份 = Item 引用（同一实体），槽位事实（position/quantity）按需散布。
 * quantity 为容器语义（组数：可充值恒 1、普通堆=件数）；可充值的 wire 数量（=可使用次数）
 * 由翻译层经 Item 引用解析——charge 不在语义层。
 * pet 仅宠物物品入包时携带（非宠物为 null，当前 remote 路径不产出宠物添加）。
 */
public sealed interface SlotChange {

    record Added(Item item, int position, int quantity, Pet pet) implements SlotChange {
        public Added(Item item, int position, int quantity) {
            this(item, position, quantity, null);
        }
    }

    record QuantityUpdated(Item item, int position, int quantity) implements SlotChange {
    }

    record Moved(Item item, int oldPosition, int position) implements SlotChange {
    }

    record Removed(Item item, int position) implements SlotChange {
    }
}
