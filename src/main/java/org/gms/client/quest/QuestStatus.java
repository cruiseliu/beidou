package org.gms.client.quest;

import org.gms.util.AssertUtil;

public enum QuestStatus {
    NOT_STARTED(0),
    STARTED(1),
    COMPLETED(2);

    final int value;

    QuestStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static QuestStatus fromValue(int value) {
        for (QuestStatus s : QuestStatus.values()) {
            if (s.getValue() == value) {
                return s;
            }
        }
        throw AssertUtil.never("bad quest status value: " + value);
    }
}
