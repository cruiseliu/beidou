package org.gms.remote.modules.npc.client;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventReceiver;
import org.gms.remote.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NPC 域入站接收：语义事件 → Handler 裸参数直调，本类只解包。
 */
public final class NpcInbound implements ClientEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(NpcInbound.class);

    @Override
    public Module module() {
        return Module.NPC;
    }

    @Override
    public void receive(Player player, ClientEvent event) {
        switch (event) {
            case NpcTalkMoreEvent e -> player.clientEventHandlers().npc()
                    .talkMore(e.lastMsg(), e.action(), e.text(), e.selection());
            default -> log.error("NpcInbound 收到非本模块事件 {}", event.getClass().getName());
        }
    }
}
