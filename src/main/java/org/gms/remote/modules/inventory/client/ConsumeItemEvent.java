package org.gms.remote.modules.inventory.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * USE_ITEM 解码结果：消耗品使用意图（slot = USE 背包槽位，itemId = 声明的道具 id）。
 * 消耗类别（解除药水/回城卷/普通药水）与效果应用归 gameplay 按 itemId 分流；
 * 与 PET_FOOD 的 {@link UseItemEvent}（脚本钩子喂食路径）是两个 gameplay 入口。
 */
public record ConsumeItemEvent(int slot, int itemId) implements ClientEvent {

    @Override
    public Module module() {
        return Module.INVENTORY;
    }
}
