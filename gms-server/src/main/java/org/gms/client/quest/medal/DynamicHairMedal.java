package org.gms.client.quest.medal;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestInfo;
import org.gms.client.quest.QuestStatus;
import org.gms.client.quest.QuestWz;

public final class DynamicHairMedal {
    public static final int QUEST_ID = 29020;
    public static final int REQUIRED_CHANGES = 50;

    private DynamicHairMedal() {
    }

    public static void onHairChanged(Character player, int oldHair, int newHair) {
        QuestInfo status = player.getQuestNoAdd(QuestWz.getInstance(QUEST_ID));
        if (oldHair / 10 == newHair / 10 || status == null || status.getStatus() != QuestStatus.STARTED) {
            return;
        }

        int progress = getProgress(status);
        if (progress < REQUIRED_CHANGES) {
            player.setQuestProgress(QUEST_ID, 0, Integer.toString(Math.min(progress + 1, REQUIRED_CHANGES)));
        }
    }

    private static int getProgress(QuestInfo status) {
        try {
            return Integer.parseInt(status.getProgress(0));
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }
}
