package org.gms.net.server.channel.handlers;

import org.gms.client.character.Character;
import org.gms.client.quest.Quest;
import org.gms.client.quest.QuestStatus;
import org.gms.client.quest.QuestWz;
import org.gms.client.Client;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.scripting.quest.QuestScriptManager;

/**
 * @author Xari
 */
public class RaiseUIStateHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        // strand 迁移（doc/13 §20 全量收口）：player 域读写，经 queued 通道归会话 strand。
        return true;
    }


    @Override
    public final void handlePacket(InPacket p, Client c) {
        int infoNumber = p.readShort();

        if (c.tryacquireClient()) {
            try {
                Character chr = c.getPlayer();
                Quest mqs = chr.getQuest(QuestWz.getInstanceFromInfoNumber(infoNumber).getId());

                QuestScriptManager.getInstance().raiseOpen(c, (short) infoNumber, mqs.getNpc());

                if (mqs.getStatus() == QuestStatus.NOT_STARTED) {
                    mqs.forceStart(chr, 22000);
                    c.getAbstractPlayerInteraction().setQuestProgress(mqs.getId(), infoNumber, 0);
                } else if (mqs.getStatus() == QuestStatus.STARTED) {
                    chr.announceQuestState(mqs, mqs.getInfoNumber() > 0);
                }
            } finally {
                c.releaseClient();
            }
        }
    }
}
