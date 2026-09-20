package org.gms.net.server.channel.handlers;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.client.QuestStatus;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.client.quest.QuestWz;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.server.ItemInformationProvider;
import org.gms.server.ItemInformationProvider.QuestConsItem;
import org.gms.util.PacketCreator;

import java.util.Map;

/**
 * @author Xari
 * @author Ronan - added concurrency protection and quest progress limit
 */
public class RaiseIncExpHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        // strand 迁移（doc/13 §20 全量收口）：player 域读写，经 queued 通道归会话 strand。
        return true;
    }


    @Override
    public final void handlePacket(InPacket p, Client c) {
        byte inventorytype = p.readByte();//nItemIT
        short slot = p.readShort();//nSlotPosition
        int itemid = p.readInt();//nItemID

        if (c.tryacquireClient()) {
            try {
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                QuestConsItem consItem = ii.getQuestConsumablesInfo(itemid);
                if (consItem == null) {
                    return;
                }

                int infoNumber = consItem.questid;
                Map<Integer, Integer> consumables = consItem.items;

                Character chr = c.getPlayer();
                QuestWz quest = QuestWz.getInstanceFromInfoNumber(infoNumber);
                if (!chr.getQuest(quest).getStatus().equals(QuestStatus.Status.STARTED)) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                int consId;
                InventoryTab inv = chr.getInventory(InventoryType.getByType(inventorytype));
                inv.lockInventory();
                try {
                    consId = inv.getItem(slot).getItemId();
                    if (!consumables.containsKey(consId) || !chr.haveItem(consId)) {
                        return;
                    }

                    InventoryManipulator.removeFromSlot(c, InventoryType.getByType(inventorytype), slot, (short) 1, false, true);
                } finally {
                    inv.unlockInventory();
                }

                int questid = quest.getId();
                int nextValue = Math.min(consumables.get(consId) + c.getAbstractPlayerInteraction().getQuestProgressInt(questid, infoNumber), consItem.exp * consItem.grade);
                c.getAbstractPlayerInteraction().setQuestProgress(questid, infoNumber, nextValue);

                c.sendPacket(PacketCreator.enableActions());
            } finally {
                c.releaseClient();
            }
        }
    }
}
