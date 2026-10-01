package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.PetPacket;
import org.gms.remote.gms083.client.translate.PetInTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/** 宠物域 in-route：SPAWN_PET（召唤/下阵/孵化意图）。 */
public final class PetInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case SPAWN_PET -> emit(opcode, in, PetPacket::decode, PetInTranslator::new, player);
            default -> {
                return false;
            }
        }
        return true;
    }
}
