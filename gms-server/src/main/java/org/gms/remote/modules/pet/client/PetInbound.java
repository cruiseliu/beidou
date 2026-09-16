package org.gms.remote.modules.pet.client;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventReceiver;
import org.gms.remote.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 宠物域入站接收：召唤/下阵意图 → 宠物 Handler 裸参数直调。 */
public final class PetInbound implements ClientEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(PetInbound.class);

    @Override
    public Module module() {
        return Module.PET;
    }

    @Override
    public void receive(Player player, ClientEvent event) {
        switch (event) {
            case SummonPetEvent(var slot, var lead) -> player.clientEventHandlers().pet().summonPet(slot, lead);
            default -> log.error("PetInbound 收到非本模块事件 {}", event.getClass().getName());
        }
    }
}
