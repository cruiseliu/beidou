/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

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
import org.gms.client.Client;
import org.gms.client.pet.Pet;
import org.gms.client.quest.QuestWz;
import org.gms.client.quest.QuestActionType;
import org.gms.provider.Data;

/**
 * @author Ronan
 */
public class PetSpeedAction extends AbstractQuestAction {

    public PetSpeedAction(QuestWz quest, Data data) {
        super(QuestActionType.PETTAMENESS, quest);
        questID = quest.getId();
    }


    @Override
    public void processData(Data data) {}

    @Override
    public void run(Character chr, Integer extSelection) {
        Client c = chr.getClient();

        Pet pet = chr.getPet(0);   // assuming here only the pet leader will gain owner speed
        if (pet == null) {
            return;
        }

        c.lockClient();
        try {
            pet.setFlag(Pet.PetFlag.OWNER_SPEED);
        } finally {
            c.unlockClient();
        }

    }
} 
