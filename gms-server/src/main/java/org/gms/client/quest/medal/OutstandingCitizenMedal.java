package org.gms.client.quest.medal;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestInfo;
import org.gms.client.quest.QuestStatus;
import org.gms.client.FamilyEntry;

public final class OutstandingCitizenMedal {
    public static final int QUEST_ID = 29508;
    public static final int ELIGIBILITY_QUEST_ID = 29580;
    public static final int MEDAL_ID = 1142081;

    private OutstandingCitizenMedal() {
    }

    public static boolean isEligible(Character player) {
        FamilyEntry familyEntry = player.getFamilyEntry();
        return player.isMarried()
                && player.getGuildId() > 0
                && familyEntry != null
                && familyEntry.getJuniorCount() >= 1;
    }

    public static void refreshEligibility(Character player) {
        QuestStatus mainStatus = player.getQuest(QUEST_ID).getStatus();
        QuestStatus eligibilityStatus = player.getQuest(ELIGIBILITY_QUEST_ID).getStatus();

        if (mainStatus != QuestStatus.STARTED || !isEligible(player)) {
            if (eligibilityStatus != QuestStatus.NOT_STARTED) {
                player.getQuestNAdd(ELIGIBILITY_QUEST_ID).reset(player);
            }
            return;
        }

        if (eligibilityStatus != QuestStatus.STARTED) {
            player.getQuestNAdd(ELIGIBILITY_QUEST_ID).forceStart(player, 9000040);
        }
    }

    public static void clearEligibility(Character player) {
        if (player.getQuest(ELIGIBILITY_QUEST_ID).getStatus() != QuestStatus.NOT_STARTED) {
            player.getQuestNAdd(ELIGIBILITY_QUEST_ID).reset(player);
        }
    }
}
