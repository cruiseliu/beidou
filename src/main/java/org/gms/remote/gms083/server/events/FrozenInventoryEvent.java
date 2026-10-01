package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;
import org.gms.remote.modules.inventory.server.SlotChange;
import org.gms.remote.modules.pet.server.PetSnap;

import java.util.List;

/**
 * freeze 的产物：入域时冻结的背包变更序列（v83 自有事件，由 V83RemoteClient 消费）。
 * 宠物槽位的 Added 已解析为 {@link Element.PetBody}（body 所需数据就绪），
 * 其余 SlotChange 原样透传；元素顺序 = 发生序，翻译时逐元素展开，wire 帧序不变。
 */
public final class FrozenInventoryEvent implements ServerEventBase {

    public sealed interface Element {
        /** 非宠物槽位：语义变更原样透传 */
        record Passthrough(SlotChange change) implements Element {
        }

        /** 宠物槽位 Added：pos = 物品体槽位，body 由 PetSnap 填充 */
        record PetBody(short pos, int itemId, PetSnap snap) implements Element {
        }
    }

    private final List<Element> elements;

    public FrozenInventoryEvent(List<Element> elements) {
        this.elements = List.copyOf(elements);
    }

    public List<Element> elements() {
        return elements;
    }
}
