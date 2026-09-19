package org.gms.remote.modules.cashshop.client;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventReceiver;
import org.gms.remote.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 商城域入站接收：语义事件 → Handler 裸参数直调，本类只解包。
 */
public final class CashShopInbound implements ClientEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(CashShopInbound.class);

    @Override
    public Module module() {
        return Module.CASHSHOP;
    }

    @Override
    public void receive(Player player, ClientEvent event) {
        switch (event) {
            case LeaveCashShopEvent e -> player.clientEventHandlers().cashShop().leaveCashShop();
            default -> log.error("CashShopInbound 收到非本模块事件 {}", event.getClass().getName());
        }
    }
}
