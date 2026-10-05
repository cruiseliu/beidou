package org.gms.remote.modules.inventory.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 使用道具意图（PET_FOOD / USE_ITEM / USE_RETURN_SCROLL 共用——消耗类别与效果应用
 * 由 gameplay 在共用 Handler 入口按优先级分流：脚本钩子 → 特判 → wz 效果数据）。
 *
 * @param slot   USE 背包槽位
 * @param itemId 声明的道具 id
 */
public record UseItemEvent(int slot, int itemId) implements ClientEvent {

    @Override
    public Module module() {
        return Module.INVENTORY;
    }
}
