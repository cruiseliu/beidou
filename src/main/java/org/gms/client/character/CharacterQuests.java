package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.quest.Quest;
import org.gms.client.quest.QuestInfo;
import org.gms.client.quest.QuestStatus;
import org.gms.client.quest.QuestWz;
import org.gms.constants.game.DelayedQuestUpdate;
import org.gms.constants.inventory.ItemConstants;
import org.gms.model.json.CharacterQuestsData;
import org.gms.remote.modules.quest.QuestModule;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.util.Pair;
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
 * 任务模块组件：任务状态（quests map）+ 任务点数（questFame）+ 限时任务（questExpirations/questExpireTask）+ 任务封包延迟。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getQuest/updateQuestStatus/... 对外转发）。
 *
 * 线程纪律：任务状态全部属 owner strand（无锁）；跨线程入口（怪物击杀、到期 tick、
 * EIM 脚本）由调用方 strand 跳板进入，不在本类内加锁。
 * 边界：只承载任务语义——任务状态、任务点数、限时任务、任务更新封包（npcUpdateQuests 延迟队列）。
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

    /** 任务更新封包延迟队列（NPC 脚本对话期间积压；全部读写已 strand 收口，无需队列锁） */
    private final List<Pair<DelayedQuestUpdate, Object[]>> npcUpdateQuests = new LinkedList<>();

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
        List<CharacterQuestsData.QuestEntryData> entries = null;
        for (Quest qs : getQuestValues()) {
            if (entries == null) {
                entries = new ArrayList<>();
            }
            CharacterQuestsData.QuestEntryData e = new CharacterQuestsData.QuestEntryData();
            e.quest = qs.getId();
            e.status = qs.getStatus().getValue();
            e.completionTime = qs.getCompletionTime();
            if (!qs.getProgress().isEmpty()) {
                e.progress = new java.util.LinkedHashMap<>(qs.getProgress());
            }
            if (!qs.getMedalMaps().isEmpty()) {
                e.medalMaps = new ArrayList<>(qs.getMedalMaps());
            }
            entries.add(e);
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
        for (CharacterQuestsData.QuestEntryData e : data.quests) {
            Quest qs = new Quest(QuestWz.getInstance(e.quest), QuestStatus.fromValue(e.status));
            qs.setCompletionTime(e.completionTime);
            if (e.progress != null) {
                for (Map.Entry<Integer, String> p : e.progress.entrySet()) {
                    qs.setProgress(p.getKey(), p.getValue());
                }
            }
            if (e.medalMaps != null) {
                for (int mapid : e.medalMaps) {
                    qs.addMedalMap(mapid);
                }
            }
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

        announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, false);
        if (qs.getInfoNumber() > 0) {
            announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, true);
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
                    announceUpdateQuest(DelayedQuestUpdate.UPDATE, quest, false);
                    if (quest.getInfoNumber() > 0) {
                        announceUpdateQuest(DelayedQuestUpdate.UPDATE, quest, true);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Character.mobKilled. chrId {}, last quest processed: {}", owner.getId(), lastQuestProcessed, e);
        }
    }

    // ── 任务更新封包（延迟队列） ──

    private void announceUpdateQuestInternal(Pair<DelayedQuestUpdate, Object[]> questUpdate) {
        Object[] objs = questUpdate.getRight();
        QuestModule quest = owner.remote().quest();

        switch (questUpdate.getLeft()) {
            case UPDATE:
                // 冲刷期解析（与旧 lazy 语义一致）：infoNumber 分支读关联任务的当前进度
                Quest qs = (Quest) objs[0];
                if ((Boolean) objs[1]) {
                    Quest iqs = owner.getQuest(qs.getInfoNumber());
                    quest.updateQuestState(iqs.getId(), iqs.getStatus().getValue(), iqs.getProgress());
                } else {
                    quest.updateQuestState(qs.getId(), qs.getStatus().getValue(), qs.getProgress());
                }
                break;

            case FORFEIT:
                // 任务 id 生产方有 short（历史调用点）与 int（getId 迁移后）两种装箱——经 Number 取值
                quest.questForfeited(((Number) objs[0]).intValue());
                break;

            case COMPLETE:
                quest.questCompleted(((Number) objs[0]).intValue(), (Long) objs[1]);
                break;

            case INFO:
                Quest info = (Quest) objs[0];
                quest.updateQuestNpcDelivery(info.getId(), info.getNpc());
                break;

            case START:
                // 接取全量通知（多帧合一）：冲刷期解析（与旧 lazy 语义一致），读各任务当前状态；
                // infoNumber 条件已在入队时点求值（objs[1]），关联任务经 getQuest 取用
                //（不存在则照旧自动建项）
                Quest started = (Quest) objs[0];
                if ((Boolean) objs[1]) {
                    Quest infoQuest = owner.getQuest(started.getInfoNumber());
                    quest.questStarted(started.getId(), started.getStatus().getValue(), started.getNpc(),
                            started.getProgress(), infoQuest.getId(), infoQuest.getStatus().getValue(),
                            infoQuest.getProgress());
                } else {
                    quest.questStarted(started.getId(), started.getStatus().getValue(), started.getNpc(),
                            started.getProgress());
                }
                break;
        }
    }

    void announceUpdateQuest(DelayedQuestUpdate questUpdateType, Object... params) {
        Pair<DelayedQuestUpdate, Object[]> p = new Pair<>(questUpdateType, params);
        Client c = owner.getClient();
        if (c.getQM() != null || c.getCM() != null) {
            npcUpdateQuests.add(p);
        } else {
            announceUpdateQuestInternal(p);
        }
    }

    void flushDelayedUpdateQuests() {
        List<Pair<DelayedQuestUpdate, Object[]>> qmQuestUpdateList = new ArrayList<>(npcUpdateQuests);
        npcUpdateQuests.clear();

        for (Pair<DelayedQuestUpdate, Object[]> q : qmQuestUpdateList) {
            announceUpdateQuestInternal(q);
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
