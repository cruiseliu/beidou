package org.gms.remote.modules.battle.client;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventReceiver;
import org.gms.remote.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 战斗域入站接收：近战攻击意图 → 战斗 Handler 裸参数直调。 */
public final class BattleInbound implements ClientEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(BattleInbound.class);

    @Override
    public Module module() {
        return Module.BATTLE;
    }

    @Override
    public void receive(Player player, ClientEvent event) {
        switch (event) {
            case CloseRangeAttackEvent(var attack) -> player.clientEventHandlers().battle().closeRangeAttack(attack);
            default -> log.error("BattleInbound 收到非本模块事件 {}", event.getClass().getName());
        }
    }
}
