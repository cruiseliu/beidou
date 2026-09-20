package org.gms.remote.modules.quest.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 放弃任务意图（QUEST_ACTION action=3）。放弃条件校验与执行归 gameplay 任务域。
 *
 * @param questId 任务 id
 */
public record ForfeitQuestEvent(int questId) implements ClientEvent {

    @Override
    public Module module() {
        return Module.QUEST;
    }
}
