package org.gms.remote;

import org.gms.client.inventory.Item;
import org.gms.client.pet.Pet;

/**
 * 背包槽位变更的语义记录：构造时冻结的事实快照。quantity 为容器语义（组数），
 * 可充值 wire 数量由翻译层解析——charge 不在语义层。
 * TODO: pet 字段仍为活引用（快照化需 PetData 化），待 pet update 原语重设计。
 * 契约见 gms-server/doc/package-client.md §1。
 */
public sealed interface SlotChange {

    record Added(Item item, int position, int quantity, Pet pet) implements SlotChange {
        public Added {
            item = item.copy();
        }

        public Added(Item item, int position, int quantity) {
            this(item, position, quantity, null);
        }
    }

    record QuantityUpdated(Item item, int position, int quantity) implements SlotChange {
        public QuantityUpdated {
            item = item.copy();
        }
    }

    record Moved(Item item, int oldPosition, int position) implements SlotChange {
        public Moved {
            item = item.copy();
        }
    }

    record Removed(Item item, int position) implements SlotChange {
        public Removed {
            item = item.copy();
        }
    }
}
