package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.QuestStatus;
import org.gms.client.quest.QuestWz;
import org.gms.config.GameConfig;
import org.gms.constants.game.DelayedQuestUpdate;
import org.gms.constants.id.MobId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.model.json.CharacterQuestsData;
import org.gms.net.server.Server;
import org.gms.remote.modules.quest.QuestModule;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.scripting.quest.esm.EsmQuests;
import org.gms.server.TimerManager;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 任务模块组件：任务状态（quests map）+ 任务点数（questFame）+ 限时任务（questExpirations/questExpireTask）+ 任务封包延迟。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getQuest/updateQuestStatus/... 对外转发）。
 *
 * 边界：只承载任务语义——任务状态、任务点数、限时任务、任务更新封包（npcUpdateQuests 延迟队列）。
 * 组队任务（party quest，AriantColiseum/MonsterCarnival/partyQuest 字段）不属本组件；
 * 持久化走 character_json 的 quests 域（toData/applyData，queststatus 三表已下线）；
 * 依赖经 owner 门面调用（sendPacket/getClient/getInventory/gainFame/...）。
 */
class CharacterQuests implements QuestModule.Handler {
    private static final Logger log = LoggerFactory.getLogger(CharacterQuests.class);

    private final Character owner;

    /** 任务点数（持久化到 characters.fquest） */
    private int questFame;

    /** 任务状态表（键 = 任务 id；int 键——QuestWz id 语义值，避免 get/put 侧的 short 强转） */
    private final Map<Integer, QuestStatus> quests;

    /** 限时任务到期表 */
    private Map<QuestWz, Long> questExpirations = new LinkedHashMap<>();
    /** 限时任务检查定时器 */
    private ScheduledFuture<?> questExpireTask = null;

    /** 任务更新封包延迟队列（NPC 脚本对话期间积压） */
    private final List<Pair<DelayedQuestUpdate, Object[]>> npcUpdateQuests = new LinkedList<>();

    /** 限时任务表锁（原 Character.evtLock 职责拆分） */
    private final Lock questLock = new ReentrantLock(true);

    CharacterQuests(Character owner) {
        this.owner = owner;
        this.quests = new LinkedHashMap<>();
    }

    // ── 查询 ──

    Map<Integer, QuestStatus> getQuests() {
        return quests;
    }

    int getQuestFame() {
        return questFame;
    }

    void setQuestFame(int questFame) {
        this.questFame = questFame;
    }

    /** 快照（saveCharToDB 持久化用；包内可见） */
    List<QuestStatus> getQuestValues() {
        synchronized (quests) {
            return new ArrayList<>(quests.values());
        }
    }

    // ── 持久化数据转换（quests 域；信封组装在 Character.toData，加载经 loadDataFromJson → applyData）──

    /** 持久化快照：无任务返回 null（不落库） */
    CharacterQuestsData toData() {
        List<CharacterQuestsData.QuestEntryData> entries = null;
        for (QuestStatus qs : getQuestValues()) {
            if (entries == null) {
                entries = new ArrayList<>();
            }
            CharacterQuestsData.QuestEntryData e = new CharacterQuestsData.QuestEntryData();
            e.quest = qs.getQuest().getId();
            e.status = qs.getStatus().getId();
            e.completionTime = qs.getCompletionTime();
            e.expirationTime = qs.getExpirationTime();
            e.forfeited = qs.getForfeited();
            e.completed = qs.getCompleted();
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
        synchronized (quests) {
            quests.clear();
            for (CharacterQuestsData.QuestEntryData e : data.quests) {
                QuestStatus qs = new QuestStatus(QuestWz.getInstance(e.quest), QuestStatus.Status.getById(e.status));
                qs.setCompletionTime(e.completionTime);
                qs.setExpirationTime(e.expirationTime);
                qs.setForfeited(e.forfeited);
                qs.setCompleted(e.completed);
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
                quests.put((int) qs.getQuestID(), qs);
            }
        }
    }

    List<QuestStatus> getCompletedQuests() {
        List<QuestStatus> ret = new LinkedList<>();
        for (QuestStatus qs : getQuestValues()) {
            if (qs.getStatus().equals(QuestStatus.Status.COMPLETED)) {
                ret.add(qs);
            }
        }

        return Collections.unmodifiableList(ret);
    }

    byte getQuestStatus(final int quest) {
        synchronized (quests) {
            QuestStatus mqs = quests.get(quest);
            if (mqs != null) {
                return (byte) mqs.getStatus().getId();
            } else {
                return 0;
            }
        }
    }

    /**
     * 强制开始任务（脚本/对话入口；原 AbstractPlayerInteraction.startQuest 的实现归位）。
     */
    boolean forceStartQuest(int questId, int npc) {
        return QuestWz.getInstance(questId).forceStart(owner, npc);
    }

    /** 强制完成任务（同上归位）。 */
    boolean forceCompleteQuest(int questId, int npc) {
        return QuestWz.getInstance(questId).forceComplete(owner, npc);
    }

    /** 任务是否已完成（未接取视为未完成；原 APII.isQuestCompleted 的 NPE 捕获语义显式化）。 */
    boolean isQuestCompleted(int questId) {
        return getQuest(questId).getStatus() == QuestStatus.Status.COMPLETED;
    }

    QuestStatus getQuest(final int quest) {
        return getQuest(QuestWz.getInstance(quest));
    }

    QuestStatus getQuest(QuestWz quest) {
        synchronized (quests) {
            short questid = quest.getId();
            QuestStatus qs = quests.get((int) questid);
            if (qs == null) {
                qs = new QuestStatus(quest, QuestStatus.Status.NOT_STARTED);
                quests.put((int) questid, qs);
            }
            return qs;
        }
    }

    QuestStatus getQuestNAdd(final QuestWz quest) {
        synchronized (quests) {
            if (!quests.containsKey((int) quest.getId())) {
                final QuestStatus status = new QuestStatus(quest, QuestStatus.Status.NOT_STARTED);
                quests.put((int) quest.getId(), status);
                return status;
            }
            return quests.get((int) quest.getId());
        }
    }

    QuestStatus getQuestNoAdd(final QuestWz quest) {
        synchronized (quests) {
            return quests.get(quest.getId());
        }
    }

    List<QuestStatus> getStartedQuests() {
        List<QuestStatus> ret = new LinkedList<>();
        for (QuestStatus qs : getQuestValues()) {
            if (QuestStatus.Status.STARTED.equals(qs.getStatus())) {
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
        QuestWz q = QuestWz.getInstance(id);
        QuestStatus qs = getQuest(q);

        if (qs.getInfoNumber() == infoNumber && infoNumber > 0) {
            QuestWz iq = QuestWz.getInstance(infoNumber);
            QuestStatus iqs = getQuest(iq);
            iqs.setProgress(0, progress);
        } else {
            qs.setProgress(infoNumber, progress);   // quest progress is thoroughly a string match, infoNumber is actually another questid
        }

        announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, false);
        if (qs.getInfoNumber() > 0) {
            announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, true);
        }
    }

    void updateQuestStatus(QuestStatus qs) {
        synchronized (quests) {
            quests.put((int) qs.getQuestID(), qs);
        }
        if (qs.getStatus().equals(QuestStatus.Status.STARTED)) {
            announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, false);
            if (qs.getInfoNumber() > 0) {
                announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, true);
            }
            announceUpdateQuest(DelayedQuestUpdate.INFO, qs);
        } else if (qs.getStatus().equals(QuestStatus.Status.COMPLETED)) {
            QuestWz mquest = qs.getQuest();
            short questid = mquest.getId();
            if (!mquest.isSameDayRepeatable() && !QuestWz.isExploitableQuest(questid)) {
                awardQuestPoint(GameConfig.getServerInt("quest_point_per_quest_complete"));
            }
            qs.setCompleted(qs.getCompleted() + 1);   // Jayd's idea - count quest completed

            announceUpdateQuest(DelayedQuestUpdate.COMPLETE, questid, qs.getCompletionTime());
            //announceUpdateQuest(DelayedQuestUpdate.INFO, qs); // happens after giving rewards, for non-next quests only
        } else if (qs.getStatus().equals(QuestStatus.Status.NOT_STARTED)) {
            announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, false);
            if (qs.getInfoNumber() > 0) {
                announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, true);
            }
            // reminder: do not reset quest progress of infoNumbers, some quests cannot backtrack
        }
    }

    void awardQuestPoint(int awardedPoints) {
        if (GameConfig.getServerInt("quest_point_requirement") < 1 || awardedPoints < 1) {
            return;
        }

        int delta;
        synchronized (quests) {
            questFame += awardedPoints;

            delta = questFame / GameConfig.getServerInt("quest_point_requirement");
            questFame %= GameConfig.getServerInt("quest_point_requirement");
        }

        if (delta > 0) {
            owner.gainFame(delta);
        }
    }

    void raiseQuestMobCount(int id) {
        // It seems nexon uses monsters that don't exist in the WZ (except string) to merge multiple mobs together for these 3 monsters.
        // We also want to run mobKilled for both since there are some quest that don't use the updated ID...
        if (id == MobId.GREEN_MUSHROOM || id == MobId.DEJECTED_GREEN_MUSHROOM) {
            raiseQuestMobCount(MobId.GREEN_MUSHROOM_QUEST);
        } else if (id == MobId.ZOMBIE_MUSHROOM || id == MobId.ANNOYED_ZOMBIE_MUSHROOM) {
            raiseQuestMobCount(MobId.ZOMBIE_MUSHROOM_QUEST);
        } else if (id == MobId.GHOST_STUMP || id == MobId.SMIRKING_GHOST_STUMP) {
            raiseQuestMobCount(MobId.GHOST_STUMP_QUEST);
        }

        int lastQuestProcessed = 0;
        try {
            synchronized (quests) {
                for (QuestStatus qs : getQuestValues()) {
                    lastQuestProcessed = qs.getQuest().getId();
                    if (qs.getStatus() == QuestStatus.Status.COMPLETED || qs.getQuest().canComplete(owner, null)) {
                        continue;
                    }

                    if (qs.progress(id)) {
                        announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, false);
                        if (qs.getInfoNumber() > 0) {
                            announceUpdateQuest(DelayedQuestUpdate.UPDATE, qs, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Character.mobKilled. chrId {}, last quest processed: {}", owner.getId(), lastQuestProcessed, e);
        }
    }

    // ── 任务更新封包（延迟队列） ──

    private void announceUpdateQuestInternal(Character chr, Pair<DelayedQuestUpdate, Object[]> questUpdate) {
        Object[] objs = questUpdate.getRight();

        switch (questUpdate.getLeft()) {
            case UPDATE:
                owner.sendPacket(PacketCreator.updateQuest(chr, (QuestStatus) objs[0], (Boolean) objs[1]));
                break;

            case FORFEIT:
                owner.sendPacket(PacketCreator.forfeitQuest((Short) objs[0]));
                break;

            case COMPLETE:
                owner.sendPacket(PacketCreator.completeQuest((Short) objs[0], (Long) objs[1]));
                break;

            case INFO:
                QuestStatus qs = (QuestStatus) objs[0];
                owner.sendPacket(PacketCreator.updateQuestInfo(qs.getQuest().getId(), qs.getNpc()));
                break;
        }
    }

    void announceUpdateQuest(DelayedQuestUpdate questUpdateType, Object... params) {
        Pair<DelayedQuestUpdate, Object[]> p = new Pair<>(questUpdateType, params);
        Client c = owner.getClient();
        if (c.getQM() != null || c.getCM() != null) {
            synchronized (npcUpdateQuests) {
                npcUpdateQuests.add(p);
            }
        } else {
            announceUpdateQuestInternal(owner, p);
        }
    }

    void flushDelayedUpdateQuests() {
        List<Pair<DelayedQuestUpdate, Object[]>> qmQuestUpdateList;

        synchronized (npcUpdateQuests) {
            qmQuestUpdateList = new ArrayList<>(npcUpdateQuests);
            npcUpdateQuests.clear();
        }

        for (Pair<DelayedQuestUpdate, Object[]> q : qmQuestUpdateList) {
            announceUpdateQuestInternal(owner, q);
        }
    }

    // ── 限时任务 ──

    void reloadQuestExpirations() {
        for (QuestStatus mqs : getStartedQuests()) {
            if (mqs.getExpirationTime() > 0) {
                questTimeLimit2(mqs.getQuest(), mqs.getExpirationTime());
            }
        }
    }

    void cancelQuestExpirationTask() {
        questLock.lock();
        try {
            if (questExpireTask != null) {
                questExpireTask.cancel(false);
                questExpireTask = null;
            }
        } finally {
            questLock.unlock();
        }
    }

    void forfeitExpirableQuests() {
        questLock.lock();
        try {
            for (QuestWz quest : questExpirations.keySet()) {
                quest.forfeit(owner);
            }

            questExpirations.clear();
        } finally {
            questLock.unlock();
        }
    }

    void questExpirationTask() {
        questLock.lock();
        try {
            if (!questExpirations.isEmpty()) {
                if (questExpireTask == null) {
                    questExpireTask = TimerManager.getInstance().register(this::runQuestExpireTask, SECONDS.toMillis(10));
                }
            }
        } finally {
            questLock.unlock();
        }
    }

    private void runQuestExpireTask() {
        questLock.lock();
        try {
            long timeNow = Server.getInstance().getCurrentTime();
            List<QuestWz> expireList = new LinkedList<>();

            for (Entry<QuestWz, Long> qe : questExpirations.entrySet()) {
                if (qe.getValue() <= timeNow) {
                    expireList.add(qe.getKey());
                }
            }

            if (!expireList.isEmpty()) {
                for (QuestWz quest : expireList) {
                    quest.expireQuest(owner);
                    questExpirations.remove(quest);
                }

                if (questExpirations.isEmpty()) {
                    questExpireTask.cancel(false);
                    questExpireTask = null;
                }
            }
        } finally {
            questLock.unlock();
        }
    }

    private void registerQuestExpire(QuestWz quest, long time) {
        questLock.lock();
        try {
            if (questExpireTask == null) {
                questExpireTask = TimerManager.getInstance().register(this::runQuestExpireTask, SECONDS.toMillis(10));
            }

            questExpirations.put(quest, Server.getInstance().getCurrentTime() + time);
        } finally {
            questLock.unlock();
        }
    }

    void questTimeLimit(final QuestWz quest, int seconds) {
        registerQuestExpire(quest, SECONDS.toMillis(seconds));
        owner.sendPacket(PacketCreator.addQuestTimeLimit(quest.getId(), (int) SECONDS.toMillis(seconds)));
    }

    void questTimeLimit2(final QuestWz quest, long expires) {
        long timeLeft = expires - System.currentTimeMillis();

        if (timeLeft <= 0) {
            quest.expireQuest(owner);
        } else {
            registerQuestExpire(quest, timeLeft);
        }
    }

    /** 角色清空时释放限时任务资源（Character.empty 调用） */
    void empty() {
        questLock.lock();
        try {
            if (questExpireTask != null) {
                questExpireTask.cancel(true);
                questExpireTask = null;
            }

            if (questExpirations != null) {
                questExpirations.clear();
                questExpirations = null;
            }
        } finally {
            questLock.unlock();
        }
    }

    // ── C→S 任务意图（QUEST_ACTION，QUEST_MODULE.Handler）──

    /** 白精华丢失任务（找回提示特例） */
    private static final short LOST_WHITE_ESSENCE_QUEST = 4522;
    private static final short CAPTAIN_LATANICA_RETURN_QUEST = 4523;
    private static final int WHITE_ESSENCE = 4000381;

    void bindClientHandlers(org.gms.remote.ClientEventHandlerRegistry registry) {
        registry.registerQuest(this);
    }

    private static void sendNpcOk(Client c, int npc, String message) {
        c.sendPacket(PacketCreator.getNPCTalk(npc, (byte) 0, message, "00 00", (byte) 0));
    }

    /** 公共前置校验：NPC 必须在图上（自动接取/完成的任务豁免；距离校验已删——
     * 单机环境客户端声明即事实，用户裁定）。 */
    private boolean npcOnMap(QuestWz quest, int npcId) {
        if (quest.isAutoStart() || quest.isAutoComplete()) {
            return true;
        }

        if (owner.getMapRef().getNPCById(npcId) == null) {
            log.warn("QUEST_ACTION 拒绝: 任务 {} 的 NPC {} 不在地图 {} 上, 玩家 {}",
                    quest.getId(), npcId, owner.getMapId(), owner.getName());
            return false;
        }
        return true;
    }

    @Override
    public void startQuest(int questId, int npc) {
        QuestWz quest = QuestWz.getInstance((short) questId);
        if (!npcOnMap(quest, npc)) {
            return;
        }
        if (quest.canStart(owner, npc)) {
            boolean success = QuestScriptManager.getInstance().checkFunctionExists(owner.getClient(), quest.getId(), npc, "start");
            boolean hasScriptRequirement = quest.hasScriptRequirement(false);
            if (hasScriptRequirement && success) {
                QuestScriptManager.getInstance().start(owner.getClient(), quest.getId(), npc);
            } else {
                quest.start(owner, npc);
            }
        } else if (questId == LOST_WHITE_ESSENCE_QUEST && owner.haveItem(WHITE_ESSENCE)) {
            sendNpcOk(owner.getClient(), npc, I18nUtil.getMessage("QuestActionHandler.hasWhiteEssence.message1"));
        } else if (questId == CAPTAIN_LATANICA_RETURN_QUEST && owner.haveItem(WHITE_ESSENCE)) {
            sendNpcOk(owner.getClient(), npc, I18nUtil.getMessage("QuestActionHandler.hasWhiteEssenceForLatanica.message1"));
        } else {
            log.warn("QUEST_ACTION 拒绝: 玩家 {} 不满足任务 {} 的接取条件 (等级/道具/NPC {}), 地图 {}",
                    owner.getName(), questId, npc, owner.getMapId());
        }
    }

    @Override
    public void completeQuest(int questId, int npc, Integer selection) {
        QuestWz quest = QuestWz.getInstance((short) questId);
        if (!npcOnMap(quest, npc)) {
            return;
        }
        if (quest.canComplete(owner, npc)) {
            boolean success = QuestScriptManager.getInstance().checkFunctionExists(owner.getClient(), quest.getId(), npc, "end");
            boolean hasScriptRequirement = quest.hasScriptRequirement(true);
            if (hasScriptRequirement && success) {
                QuestScriptManager.getInstance().end(owner.getClient(), quest.getId(), npc);
            } else {
                if (selection != null) {
                    quest.complete(owner, npc, selection);
                } else {
                    quest.complete(owner, npc);
                }
            }
        } else {
            log.warn("QUEST_ACTION 拒绝: 玩家 {} 不满足任务 {} 的完成条件 (等级/道具/NPC {}), 地图 {}",
                    owner.getName(), questId, npc, owner.getMapId());
        }
    }

    @Override
    public void forfeitQuest(int questId) {
        QuestWz.getInstance((short) questId).forfeit(owner);
    }

    @Override
    public void restoreLostItem(int questId, int itemId) {
        QuestWz.getInstance((short) questId).restoreLostItem(owner, itemId);
    }

    @Override
    public void startScriptedQuest(int questId, int npc) {
        QuestWz quest = QuestWz.getInstance((short) questId);
        if (!npcOnMap(quest, npc)) {
            return;
        }
        if (quest.canStart(owner, npc)) {
            String entry = quest.getQuestScriptName(false);
            if (entry != null && EsmQuests.exists(questId)) {
                EsmQuests.start(owner, questId, npc, entry);   // ESM 新系统：入口名来自 WZ startscript（doc/13 §15）
            } else if (entry == null) {
                QuestScriptManager.getInstance().start(owner.getClient(), quest.getId(), npc);
            }
        }
    }

    @Override
    public void endScriptedQuest(int questId, int npc) {
        QuestWz quest = QuestWz.getInstance((short) questId);
        if (!npcOnMap(quest, npc)) {
            return;
        }
        if (quest.canComplete(owner, npc)) {
            String entry = quest.getQuestScriptName(true);
            if (entry != null && EsmQuests.exists(questId)) {
                EsmQuests.end(owner, questId, npc, entry);     // ESM 新系统：入口名来自 WZ endscript
            } else if (entry == null) {
                QuestScriptManager.getInstance().end(owner.getClient(), quest.getId(), npc);
            }
        } else {
            log.warn("QUEST_ACTION 拒绝: 玩家 {} 不满足任务 {} 的脚本完成条件 (NPC {}), 地图 {}",
                    owner.getName(), questId, npc, owner.getMapId());
        }
    }
}
