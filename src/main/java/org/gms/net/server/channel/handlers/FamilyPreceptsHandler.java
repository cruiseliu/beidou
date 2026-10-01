package org.gms.net.server.channel.handlers;

import org.gms.client.Client;
import org.gms.client.Family;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.util.PacketCreator;

public class FamilyPreceptsHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        // strand 迁移（doc/13 §20 全量收口）：player 域读写，经 queued 通道归会话 strand。
        return true;
    }


    @Override
    public void handlePacket(InPacket p, Client c) {
        Family family = c.getPlayer().getFamily();
        if (family == null) {
            return;
        }
        if (family.getLeader().getChr() != c.getPlayer()) {
            return; //only the leader can set the precepts
        }
        String newPrecepts = p.readString();
        if (newPrecepts.length() > 200) {
            return;
        }
        family.setMessage(newPrecepts, true);
        //family.broadcastFamilyInfoUpdate(); //probably don't need to broadcast for this?
        c.sendPacket(PacketCreator.getFamilyInfo(c.getPlayer().getFamilyEntry()));
    }

}
