package org.gms.remote.gms083.client.routers;

import org.gms.client.Player;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.remote.gms083.client.packets.PetFootPacket;
import org.gms.remote.gms083.client.translate.PetFoodTranslator;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * 背包域 in-route：PET_FOOD（使用道具意图——包型在 pet opcode 族，事件归属 inventory 域，
 * 由事件自报 module 决定接收方）。其余 item 系 opcode 迁移后归本 route。
 */
public final class InventoryInRouter extends AbstractInRouter {

    @Override
    public boolean route(RecvOpcode opcode, ByteBufReader in, Player player) {
        switch (opcode) {
            case PET_FOOD -> emit(opcode, in, PetFootPacket::decode, PetFoodTranslator::new, player);
            default -> {
                return false;
            }
        }
        return true;
    }
}
