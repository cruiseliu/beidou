package org.gms.remote.v83.out.route;

import org.gms.remote.InventoryModule;
import org.gms.remote.SlotChange;
import org.gms.remote.out.events.InventoryFullEvent;
import org.gms.remote.out.events.InventoryModsEvent;
import org.gms.remote.out.events.SemanticEvent;

import java.util.List;
import java.util.function.Consumer;

/** 背包域 route：模块调用 → 事件入域（含宠物物品的冻结在门面 dispatch 入口完成）。 */
public final class InventoryRoute implements InventoryModule {

    private final Consumer<SemanticEvent> dispatch;

    public InventoryRoute(Consumer<SemanticEvent> dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public void updateInventory(List<SlotChange> changes) {
        dispatch.accept(new InventoryModsEvent(changes));
    }

    @Override
    public void announceInventoryFull() {
        dispatch.accept(new InventoryFullEvent());
    }
}
