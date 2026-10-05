package org.gms.remote.modules.inventory.client;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventReceiver;
import org.gms.remote.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 背包域入站接收：使用道具意图 → 背包 Handler 裸参数直调（PET_FOOD 喂食在道具脚本钩子内；
 *  USE_ITEM 消耗品效果在 consumeItem）。 */
public final class InventoryInbound implements ClientEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(InventoryInbound.class);

    @Override
    public Module module() {
        return Module.INVENTORY;
    }

    @Override
    public void receive(Player player, ClientEvent event) {
        switch (event) {
            case UseItemEvent(var slot, var itemId) -> player.clientEventHandlers().inventory().useItem(slot, itemId);
            case ConsumeItemEvent(var slot, var itemId) -> player.clientEventHandlers().inventory().consumeItem(slot, itemId);
            default -> log.error("InventoryInbound 收到非本模块事件 {}", event.getClass().getName());
        }
    }
}
