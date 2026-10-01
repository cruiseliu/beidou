package org.gms.constants.game;

public enum DelayedQuestUpdate {
    UPDATE, FORFEIT, COMPLETE, INFO,
    START   // 接取全量通知（多帧合一，展开为单个 QuestStartEvent；obj = [Quest, 有无 infoNumber 同步]）
}
