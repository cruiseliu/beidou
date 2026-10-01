package org.gms.remote.modules.quest.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * 找回任务丢失物品意图（QUEST_ACTION action=0）。wire 上 itemId 前还有一个 legacy 丢弃
 * 不解的 int（疑似 npc id）——服务端不消费，不入事件。持有校验与找回执行归 gameplay 任务域。
 *
 * @param questId 任务 id
 * @param itemId  待找回物品 id
 */
public record RestoreLostItemEvent(int questId, int itemId) implements ClientEvent {

    @Override
    public Module module() {
        return Module.QUEST;
    }
}
