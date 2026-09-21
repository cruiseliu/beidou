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
package org.gms.client.quest.requirements;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestWz;
import org.gms.client.quest.QuestRequirementType;
import org.gms.client.quest.QuestStatus;
import org.gms.client.quest.QuestInfo;
import org.gms.provider.Data;
import org.gms.provider.DataTool;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Tyler (Twdtwd)
 */
public class IntervalRequirement extends AbstractQuestRequirement {
    private long interval = -1;
    private final int questID;

    public IntervalRequirement(QuestWz quest, Data data) {
        super(QuestRequirementType.INTERVAL);
        questID = quest.getId();
        processData(data);
    }

    public long getInterval() {
        return interval;
    }

    @Override
    public void processData(Data data) {
        interval = MINUTES.toMillis(DataTool.getInt(data));
    }

    private static String getIntervalTimeLeft(Character chr, IntervalRequirement r) {
        StringBuilder str = new StringBuilder();

        long futureTime = chr.getQuest(r.questID).getCompletionTime() + r.getInterval();
        long leftTime = futureTime - System.currentTimeMillis();

        byte mode = 0;
        if (leftTime / MINUTES.toMillis(1) > 0) {
            mode++;     //counts minutes

            if (leftTime / HOURS.toMillis(1) > 0) {
                mode++;     //counts hours
            }
        }

        switch (mode) {
            case 2:
                int hours = (int) ((leftTime / HOURS.toMillis(1)));
                str.append(hours + " hours, ");

            case 1:
                int minutes = (int) ((leftTime / MINUTES.toMillis(1)) % 60);
                str.append(minutes + " minutes, ");

            default:
                int seconds = (int) (leftTime / 1000) % 60;
                str.append(seconds + " seconds");
        }

        return str.toString();
    }

    @Override
    public boolean check(Character chr, Integer npcid) {
        boolean check = !chr.getQuest(questID).getStatus().equals(QuestStatus.COMPLETED);
        boolean check2 = chr.getQuest(questID).getCompletionTime() <= System.currentTimeMillis() - interval;

        if (check || check2) {
            return true;
        } else {
            chr.message("This quest will become available again in approximately " + getIntervalTimeLeft(chr, this) + ".");
            return false;
        }
    }
}
