package org.gms.client.quest.medal;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestInfo;
import org.gms.client.quest.QuestStatus;
import org.gms.client.quest.QuestWz;
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
        QuestWz mainQuest = QuestWz.getInstance(QUEST_ID);
        QuestWz eligibilityQuest = QuestWz.getInstance(ELIGIBILITY_QUEST_ID);
        QuestStatus mainStatus = player.getQuest(mainQuest).getStatus();
        QuestStatus eligibilityStatus = player.getQuest(eligibilityQuest).getStatus();

        if (mainStatus != QuestStatus.STARTED || !isEligible(player)) {
            if (eligibilityStatus != QuestStatus.NOT_STARTED) {
                eligibilityQuest.reset(player);
            }
            return;
        }

        if (eligibilityStatus != QuestStatus.STARTED) {
            eligibilityQuest.forceStart(player, 9000040);
        }
    }

    public static void clearEligibility(Character player) {
        QuestWz eligibilityQuest = QuestWz.getInstance(ELIGIBILITY_QUEST_ID);
        if (player.getQuest(eligibilityQuest).getStatus() != QuestStatus.NOT_STARTED) {
            eligibilityQuest.reset(player);
        }
    }
}
