package org.gms.net.server.channel.handlers;

import org.gms.client.Client;
import org.gms.client.keybind.QuickslotBinding;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;

/**
 * @author Shavit
 */
public class QuickslotKeyMappedModifiedHandler extends AbstractPacketHandler {
    @Override
    public boolean queued() {
        // strand 迁移（doc/13 §20 全量收口）：player 域读写，经 queued 通道归会话 strand。
        return true;
    }


    @Override
    public void handlePacket(InPacket p, Client c) {
        // Invalid size for the packet.
        if (p.available() != QuickslotBinding.QUICKSLOT_SIZE * Integer.BYTES ||
                // not logged in-game
                c.getPlayer() == null) {
            return;
        }

        byte[] aQuickslotKeyMapped = new byte[QuickslotBinding.QUICKSLOT_SIZE];

        for (int i = 0; i < QuickslotBinding.QUICKSLOT_SIZE; i++) {
            aQuickslotKeyMapped[i] = (byte) p.readInt();
        }

        c.getPlayer().changeQuickslotKeybinding(aQuickslotKeyMapped);
    }
}
