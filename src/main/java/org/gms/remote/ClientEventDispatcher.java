package org.gms.remote;

import org.gms.client.Player;
import org.gms.remote.modules.cashshop.client.CashShopInbound;
import org.gms.remote.modules.inventory.client.InventoryInbound;
import org.gms.remote.modules.map.client.MapInbound;
import org.gms.remote.modules.npc.client.NpcInbound;
import org.gms.remote.modules.pet.client.PetInbound;
import org.gms.remote.modules.quest.client.QuestInbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * ClientEvent 分派器（版本无关静态查表）：按事件自报的 {@link Module} 找到该域的
 * {@link ClientEventReceiver}。与 S→C 的 schedule(dest, event) 对偶——S→C 的 owner 由
 * 产出 route 自声明，C→S 的接收方由事件归属域决定，产出方（InRouter）无感知。
 * 未装配接收器的 module 收到事件 = 语义 bug，响亮记日志后丢弃。
 */
public final class ClientEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClientEventDispatcher.class);

    /** 接收器装配表（无状态单例；新增域在此登记） */
    private static final Map<Module, ClientEventReceiver> TABLE = build();

    private ClientEventDispatcher() {
    }

    private static Map<Module, ClientEventReceiver> build() {
        Map<Module, ClientEventReceiver> table = new EnumMap<>(Module.class);
        for (ClientEventReceiver receiver : List.of(new MapInbound(), new PetInbound(), new InventoryInbound(), new CashShopInbound(), new QuestInbound(), new NpcInbound())) {
            if (table.putIfAbsent(receiver.module(), receiver) != null) {
                throw new IllegalStateException("module " + receiver.module() + " 重复注册接收器");
            }
        }
        return Map.copyOf(table);
    }

    public static void dispatch(Player player, ClientEvent event) {
        ClientEventReceiver receiver = TABLE.get(event.module());
        if (receiver == null) {
            log.error("ClientEvent {} 无接收器（module {} 未装配接收类），丢弃",
                    event.getClass().getName(), event.module());
            return;
        }
        receiver.receive(player, event);
    }
}
