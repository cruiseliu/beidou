package org.gms.remote.modules.inventory.server;

import org.gms.client.inventory.Item;

/**
 * 背包槽位变更的语义记录：构造时冻结的事实快照。quantity 为容器语义（组数），
 * 可充值 wire 数量由翻译层解析——charge 不在语义层。
 * 本记录对 pet 盲：宠物物品的 body 数据由版本实现在事件入域时冻结（freeze），
 * 不经本记录承载。契约见 gms-server/doc/package-client.md §1。
 */
public sealed interface SlotChange {

    record Added(Item item, int position, int quantity) implements SlotChange {
        public Added {
            item = item.copy();
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
