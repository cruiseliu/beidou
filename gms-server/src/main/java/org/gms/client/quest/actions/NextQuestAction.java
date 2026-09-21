/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.client.quest.actions;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestWz;
import org.gms.client.quest.QuestActionType;
import org.gms.client.quest.QuestInfo;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.util.PacketCreator;

/**
 * @author Tyler (Twdtwd)
 */
public class NextQuestAction extends AbstractQuestAction {
    int nextQuest;

    public NextQuestAction(QuestWz quest, Data data) {
        super(QuestActionType.NEXTQUEST, quest);
        processData(data);
    }


    @Override
    public void processData(Data data) {
        nextQuest = DataTool.getInt(data);
    }

    @Override
    public void run(Character chr, Integer extSelection) {
        QuestInfo status = chr.getQuest(QuestWz.getInstance(questID));
        chr.sendPacket(PacketCreator.updateQuestFinish((short) questID, status.getNpc(), (short) nextQuest));
    }
} 
