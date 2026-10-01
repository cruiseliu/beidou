package org.gms.client.character;

import org.gms.client.quest.Quest;
import org.gms.client.quest.QuestInfo;
import org.gms.client.quest.QuestStatus;
import org.gms.client.quest.QuestWz;
import org.gms.constants.inventory.ItemConstants;
import org.gms.model.json.CharacterQuestsData;
import org.gms.model.json.QuestData;
import org.gms.remote.modules.quest.QuestModule;
import org.gms.remote.ClientEventHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 任务模块组件：任务状态（quests map）+ 任务点数（questFame）+ 限时任务（questExpirations/questExpireTask）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getQuest/updateQuestStatus/... 对外转发）。
 *
 * 线程纪律：任务状态全部属 owner strand（无锁）；跨线程入口（怪物击杀、到期 tick、
 * EIM 脚本）由调用方 strand 跳板进入，不在本类内加锁。
 * 边界：只承载任务语义——任务状态、任务点数、限时任务；状态帧即时展开为语义调用，
 * 会话内合并归 remote batch（doc/package-client.md §2），本组件不设发包队列。
 * 组队任务（party quest，AriantColiseum/MonsterCarnival/partyQuest 字段）不属本组件；
 * 持久化走 character_json 的 quests 域（toData/applyData，queststatus 三表已下线）；
 * 依赖经 owner 门面调用（sendPacket/getClient/getInventory/gainFame/...）。
 */
public class CharacterQuests implements QuestModule.Handler {
    private static final Logger log = LoggerFactory.getLogger(CharacterQuests.class);

    private final Character owner;

    /** 任务状态表 */
    private final Map<Integer, Quest> quests;
    private final Map<Integer, QuestInfo> infos;

    CharacterQuests(Character owner) {
        this.owner = owner;
        this.quests = new LinkedHashMap<>();
        this.infos = new HashMap<>();
    }

    // ── 查询 ──

    /** 快照（saveCharToDB 持久化用；包内可见） */
    private List<Quest> getQuestValues() {
        return new ArrayList<>(quests.values());
    }

    // ── 持久化数据转换（quests 域；信封组装在 Character.toData，加载经 loadDataFromJson → applyData）──

    /** 持久化快照：无任务返回 null（不落库） */
    CharacterQuestsData toData() {
        List<QuestData> entries = null;
        for (Quest qs : getQuestValues()) {
            if (entries == null) {
                entries = new ArrayList<>();
            }
            entries.add(qs.toData());
        }
        if (entries == null) {
            return null;
        }
        CharacterQuestsData data = new CharacterQuestsData();
        data.quests = entries;
        return data;
    }

    /** 恢复（登录装载路径，先于会话开始；旧角色 JSON 无 quests 域 = 保持空表） */
    void applyData(CharacterQuestsData data) {
        if (data == null || data.quests == null) {
            return;
        }
        quests.clear();
        for (QuestData e : data.quests) {
            Quest qs = Quest.fromData(owner, e);
            quests.put(qs.getId(), qs);
        }
    }

    List<Quest> getCompletedQuests() {
        List<Quest> ret = new LinkedList<>();
        for (Quest qs : getQuestValues()) {
            if (qs.getStatus().equals(QuestStatus.COMPLETED)) {
                ret.add(qs);
            }
        }

        return Collections.unmodifiableList(ret);
    }

    byte getQuestStatus(final int quest) {
        Quest mqs = quests.get(quest);
        if (mqs != null) {
            return (byte) mqs.getStatus().getValue();
        } else {
            return 0;
        }
    }

    /**
     * 强制开始任务（脚本/对话入口；原 AbstractPlayerInteraction.startQuest 的实现归位）。
     */
    boolean forceStartQuest(int questId, int npc) {
        return getQuest(questId).forceStart(owner, npc);
    }

    /** 强制完成任务（同上归位）。 */
    boolean forceCompleteQuest(int questId, int npc) {
        return getQuest(questId).forceComplete(owner, npc);
    }

    /** 任务是否已完成（未接取视为未完成；原 APII.isQuestCompleted 的 NPE 捕获语义显式化）。 */
    boolean isQuestCompleted(int questId) {
        return getQuest(questId).getStatus() == QuestStatus.COMPLETED;
    }

    Quest getQuest(int questId) {
        Quest quest = quests.get(questId);
        if (quest == null) {
            quest = new Quest(questId);
            quests.put(questId, quest);
        }
        return quest;
    }

    Quest getQuestNoAdd(final int questId) {
        return quests.get(questId);
    }

    List<Quest> getStartedQuests() {
        List<Quest> ret = new LinkedList<>();
        for (Quest qs : getQuestValues()) {
            if (QuestStatus.STARTED.equals(qs.getStatus())) {
                ret.add(qs);
            }
        }
        return Collections.unmodifiableList(ret);
    }

    boolean needQuestItem(int questid, int itemid) {
        if (questid <= 0) { //For non quest items :3
            return true;
        }

        int amountNeeded, questStatus = this.getQuestStatus(questid);
        if (questStatus == 0) {
            amountNeeded = QuestWz.getInstance(questid).getStartItemAmountNeeded(itemid);
            if (amountNeeded == Integer.MIN_VALUE) {
                return false;
            }
        } else if (questStatus != 1) {
            return false;
        } else {
            amountNeeded = QuestWz.getInstance(questid).getCompleteItemAmountNeeded(itemid);
            if (amountNeeded == Integer.MAX_VALUE) {
                return true;
            }
        }

        return owner.getInventory(ItemConstants.getInventoryType(itemid)).countById(itemid) < amountNeeded;
    }

    // ── 任务状态更新 ──

    void setQuestProgress(int id, int infoNumber, String progress) {
        Quest qs = getQuest(id);

        if (qs.getInfoNumber() == infoNumber && infoNumber > 0) {
            Quest iqs = getQuest(infoNumber);
            iqs.setProgress(0, progress);
        } else {
            qs.setProgress(infoNumber, progress);   // quest progress is thoroughly a string match, infoNumber is actually another questid
        }

        announceQuestState(qs, false);
        if (qs.getInfoNumber() > 0) {
            announceQuestState(qs, true);
        }
    }

    void raiseQuestMobCount(int id) {
        // It seems nexon uses monsters that don't exist in the WZ (except string) to merge multiple mobs together for these 3 monsters.
        // We also want to run mobKilled for both since there are some quest that don't use the updated ID...
        // if (id == MobId.GREEN_MUSHROOM || id == MobId.DEJECTED_GREEN_MUSHROOM) {
        //     raiseQuestMobCount(MobId.GREEN_MUSHROOM_QUEST);
        // } else if (id == MobId.ZOMBIE_MUSHROOM || id == MobId.ANNOYED_ZOMBIE_MUSHROOM) {
        //     raiseQuestMobCount(MobId.ZOMBIE_MUSHROOM_QUEST);
        // } else if (id == MobId.GHOST_STUMP || id == MobId.SMIRKING_GHOST_STUMP) {
        //     raiseQuestMobCount(MobId.GHOST_STUMP_QUEST);
        // }

        int lastQuestProcessed = 0;
        try {
            for (Quest quest : getQuestValues()) {
                lastQuestProcessed = quest.getId();
                if (quest.getStatus() == QuestStatus.COMPLETED || quest.canComplete(owner)) {
                    continue;
                }

                if (quest.progress(id)) {
                    announceQuestState(quest, false);
                    if (quest.getInfoNumber() > 0) {
                        announceQuestState(quest, true);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Character.mobKilled. chrId {}, last quest processed: {}", owner.getId(), lastQuestProcessed, e);
        }
    }

    // ── 任务状态帧（即时展开；会话内合并归 remote batch——doc/package-client.md §2，无本地队列）──

    /**
     * 任务状态/进度帧（SHOW_STATUS_INFO quest 体）。{@code companion} = true 时发的是
     * infoNumber 关联任务的当前状态帧（而非本任务）——沿用旧调用词汇，调用方按
     * 「主帧恒发 + 关联任务条件发」两段书写。
     */
    void announceQuestState(Quest qs, boolean companion) {
        QuestModule quest = owner.remote().quest();
        if (companion) {
            // 关联任务经 getQuest 取用（不存在则照旧自动建项）
            Quest iqs = owner.getQuest(qs.getInfoNumber());
            quest.updateQuestState(iqs.getId(), iqs.getStatus().getValue(), iqs.getProgress());
        } else {
            quest.updateQuestState(qs.getId(), qs.getStatus().getValue(), qs.getProgress());
        }
    }

    // ── C→S 任务意图（QUEST_ACTION，QUEST_MODULE.Handler）──

    void bindClientHandlers(ClientEventHandlerRegistry registry) {
        registry.registerQuest(this);
    }

    @Override
    public void startQuest(int questId, int npcId) {
        getQuest(questId).start(npcId);
    }

    @Override
    public void startScriptedQuest(int questId, int npcId) {
        getQuest(questId).runStartScript(npcId);
    }

    @Override
    public void completeQuest(int questId, int npcId, Integer selection) {
        getQuest(questId).complete(npcId, selection);
    }

    @Override
    public void endScriptedQuest(int questId, int npcId) {
        getQuest(questId).runEndScript(npcId);
    }

    @Override
    public void forfeitQuest(int questId) {
        getQuest(questId).forfeit();
    }

    @Override
    public void restoreLostItem(int questId, int itemId) {
        getQuest(questId).restoreLostItem(itemId);
    }

    public String getInfo(int infoNumber) {
        QuestInfo info = infos.get(infoNumber);
        return info == null ? null : info.getValue();
    }
}
