package org.gms.remote.modules.quest.client;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.ClientEventReceiver;
import org.gms.remote.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务域入站接收：语义事件 → Handler 裸参数直调，本类只解包。
 */
public final class QuestInbound implements ClientEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(QuestInbound.class);

    @Override
    public Module module() {
        return Module.QUEST;
    }

    @Override
    public void receive(Player player, ClientEvent event) {
        switch (event) {
            case StartQuestEvent e -> player.clientEventHandlers().quest().startQuest(e.questId(), e.npc());
            case CompleteQuestEvent e -> player.clientEventHandlers().quest()
                    .completeQuest(e.questId(), e.npc(), e.selection());
            case ForfeitQuestEvent e -> player.clientEventHandlers().quest().forfeitQuest(e.questId());
            case RestoreLostItemEvent e -> player.clientEventHandlers().quest()
                    .restoreLostItem(e.questId(), e.itemId());
            case ScriptedStartQuestEvent e -> player.clientEventHandlers().quest()
                    .startScriptedQuest(e.questId(), e.npc());
            case ScriptedEndQuestEvent e -> player.clientEventHandlers().quest()
                    .endScriptedQuest(e.questId(), e.npc());
            default -> log.error("QuestInbound 收到非本模块事件 {}", event.getClass().getName());
        }
    }
}
