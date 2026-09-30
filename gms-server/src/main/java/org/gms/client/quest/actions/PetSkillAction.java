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

import org.gms.client.inventory.ItemSlot;
import org.gms.client.quest.QuestWz;
import org.gms.client.quest.QuestActionType;
import org.gms.client.quest.Quest;
import org.gms.client.quest.QuestStatus;
import org.gms.client.character.Character;
import org.gms.provider.Data;
import org.gms.provider.DataTool;
import org.gms.client.inventory.ItemFlag;

/**
 * @author Tyler (Twdtwd)
 */
public class PetSkillAction extends AbstractQuestAction {
    int flag;

    public PetSkillAction(QuestWz quest, Data data) {
        super(QuestActionType.PETSKILL, quest);
        questID = quest.getId();
        processData(data);
    }


    @Override
    public void processData(Data data) {
        flag = DataTool.getInt("petskill", data);
    }

    @Override
    public boolean check(Character chr, Integer extSelection) {
        Quest status = chr.getQuest(questID);
        // if (!(status.getStatus() == QuestStatus.NOT_STARTED && status.getForfeited() > 0)) {
        //     return false;
        // }

        return chr.getPet(0) != null;
    }

    @Override
    public void run(Character chr, Integer extSelection) {
        ItemSlot petItem = chr.findPetItemSlot(chr.getPet(0).getPetId());
        if (flag == 128) {
            petItem.addFlag(ItemFlag.PET_COME);
        }
    }
} 
