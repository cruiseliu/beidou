/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.client.quest;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestStatus;
import org.gms.client.quest.actions.AbstractQuestAction;
import org.gms.client.quest.actions.BuffAction;
import org.gms.client.quest.actions.ExpAction;
import org.gms.client.quest.actions.FameAction;
import org.gms.client.quest.actions.InfoAction;
import org.gms.client.quest.actions.ItemAction;
import org.gms.client.quest.actions.MesoAction;
import org.gms.client.quest.actions.NextQuestAction;
import org.gms.client.quest.actions.PetSkillAction;
import org.gms.client.quest.actions.PetSpeedAction;
import org.gms.client.quest.actions.PetTamenessAction;
import org.gms.client.quest.actions.QuestAction;
import org.gms.client.quest.actions.SkillAction;
import org.gms.client.quest.requirements.AbstractQuestRequirement;
import org.gms.client.quest.requirements.BuffExceptRequirement;
import org.gms.client.quest.requirements.BuffRequirement;
import org.gms.client.quest.requirements.CompletedQuestRequirement;
import org.gms.client.quest.requirements.EndDateRequirement;
import org.gms.client.quest.requirements.FieldEnterRequirement;
import org.gms.client.quest.requirements.InfoExRequirement;
import org.gms.client.quest.requirements.InfoNumberRequirement;
import org.gms.client.quest.requirements.IntervalRequirement;
import org.gms.client.quest.requirements.ItemRequirement;
import org.gms.client.quest.requirements.JobRequirement;
import org.gms.client.quest.requirements.MaxLevelRequirement;
import org.gms.client.quest.requirements.MesoRequirement;
import org.gms.client.quest.requirements.MinLevelRequirement;
import org.gms.client.quest.requirements.MinTamenessRequirement;
import org.gms.client.quest.requirements.MobRequirement;
import org.gms.client.quest.requirements.MonsterBookCountRequirement;
import org.gms.client.quest.requirements.NpcRequirement;
import org.gms.client.quest.requirements.PetRequirement;
import org.gms.client.quest.requirements.QuestRequirement;
import org.gms.client.quest.requirements.ScriptRequirement;
import org.gms.config.GameConfig;
import org.gms.constants.game.DelayedQuestUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.util.PacketCreator;
import org.gms.util.StringUtil;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map.Entry;
import java.util.Set;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Matze
 * @author Ronan - support for medal quests
 */
public class QuestWz {
    private static final Logger log = LoggerFactory.getLogger(QuestWz.class);
    /** 任务静态定义注册表：懒加载（getInstance 首触 put）+ loadAllQuests 整表替换 +
     * clearCache（GM 命令）逐个/整体移除——写点分布在任意 strand，须并发容器 */
    private static volatile Map<Integer, QuestWz> quests = new ConcurrentHashMap<>();
    /** infoNumber → 任务 id 反查表：loadAllQuests 整表构建后替换，此后只读，无需并发容器 */
    private static volatile Map<Integer, Integer> infoNumberQuests = new HashMap<>();
    /** 勋章任务 viewMedalItem 侧表（键 = 任务 id）：QuestWz 构造器（任意 strand 首触）写入 */
    private static final Map<Integer, Integer> medals = new ConcurrentHashMap<>();

    private static final Set<Integer> exploitableQuests = new HashSet<>();

    static {
        exploitableQuests.add(2338);    // there are a lot more exploitable quests, they need to be nit-picked
        exploitableQuests.add(3637);
        exploitableQuests.add(3714);
        exploitableQuests.add(21752);
    }

    protected int id;
    protected int timeLimit, timeLimit2;
    protected Map<QuestRequirementType, AbstractQuestRequirement> startReqs = new EnumMap<>(QuestRequirementType.class);
    protected Map<QuestRequirementType, AbstractQuestRequirement> completeReqs = new EnumMap<>(QuestRequirementType.class);
    protected Map<QuestActionType, AbstractQuestAction> startActs = new EnumMap<>(QuestActionType.class);
    protected Map<QuestActionType, AbstractQuestAction> completeActs = new EnumMap<>(QuestActionType.class);
    protected List<Integer> relevantMobs = new LinkedList<>();
    private boolean autoStart;
    private boolean autoPreComplete, autoComplete;
    private boolean repeatable = false;
    private String name = "", parent = "";
    private final static DataProvider questData = DataProviderFactory.getDataProvider(WZFiles.QUEST);
    private final static Data questInfo = questData.getData("QuestInfo.img");
    private final static Data questAct = questData.getData("Act.img");
    private final static Data questReq = questData.getData("Check.img");

    private QuestWz(int id) {
        this.id = (short) id;

        Data reqData = questReq.getChildByPath(String.valueOf(id));
        if (reqData == null) {//most likely infoEx
            return;
        }

        if (questInfo != null) {
            Data reqInfo = questInfo.getChildByPath(String.valueOf(id));
            if (reqInfo != null) {
                name = DataTool.getString("name", reqInfo, "");
                parent = DataTool.getString("parent", reqInfo, "");

                timeLimit = DataTool.getInt("timeLimit", reqInfo, 0);
                timeLimit2 = DataTool.getInt("timeLimit2", reqInfo, 0);
                autoStart = DataTool.getInt("autoStart", reqInfo, 0) == 1;
                autoPreComplete = DataTool.getInt("autoPreComplete", reqInfo, 0) == 1;
                autoComplete = DataTool.getInt("autoComplete", reqInfo, 0) == 1;

                int medalid = DataTool.getInt("viewMedalItem", reqInfo, 0);
                if (medalid != 0) {
                    medals.put((int) this.id, medalid);
                }
            } else {
                log.warn("No quest data for id {}", id);
            }
        }

        Data startReqData = reqData.getChildByPath("0");
        if (startReqData != null) {
            for (Data startReq : startReqData.getChildren()) {
                QuestRequirementType type = QuestRequirementType.getByWZName(startReq.getName());
                switch (type) {
                case INTERVAL:
                    repeatable = true;
                    break;
                case MOB:
                    for (Data mob : startReq.getChildren()) {
                        relevantMobs.add(DataTool.getInt(mob.getChildByPath("id")));
                    }
                    break;
                }

                AbstractQuestRequirement req = this.getRequirement(type, startReq);
                if (req == null) {
                    continue;
                }

                startReqs.put(type, req);
            }
        }

        Data completeReqData = reqData.getChildByPath("1");
        if (completeReqData != null) {
            for (Data completeReq : completeReqData.getChildren()) {
                QuestRequirementType type = QuestRequirementType.getByWZName(completeReq.getName());

                AbstractQuestRequirement req = this.getRequirement(type, completeReq);
                if (req == null) {
                    continue;
                }

                if (type.equals(QuestRequirementType.MOB)) {
                    for (Data mob : completeReq.getChildren()) {
                        relevantMobs.add(DataTool.getInt(mob.getChildByPath("id")));
                    }
                }
                completeReqs.put(type, req);
            }
        }
        Data actData = questAct.getChildByPath(String.valueOf(id));
        if (actData == null) {
            return;
        }
        final Data startActData = actData.getChildByPath("0");
        if (startActData != null) {
            for (Data startAct : startActData.getChildren()) {
                QuestActionType questActionType = QuestActionType.getByWZName(startAct.getName());
                AbstractQuestAction act = this.getAction(questActionType, startAct);

                if (act == null) {
                    continue;
                }

                startActs.put(questActionType, act);
            }
        }
        Data completeActData = actData.getChildByPath("1");
        if (completeActData != null) {
            for (Data completeAct : completeActData.getChildren()) {
                QuestActionType questActionType = QuestActionType.getByWZName(completeAct.getName());
                AbstractQuestAction act = this.getAction(questActionType, completeAct);

                if (act == null) {
                    continue;
                }

                completeActs.put(questActionType, act);
            }
        }
    }

    public boolean isAutoComplete() {
        return autoPreComplete || autoComplete;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public static QuestWz getInstance(int id) {
        return quests.computeIfAbsent(id, QuestWz::new);
    }

    public static QuestWz getInstanceFromInfoNumber(int infoNumber) {
        Integer id = infoNumberQuests.get(infoNumber);
        if (id == null) {
            id = infoNumber;
        }

        return getInstance(id);
    }

    public boolean isSameDayRepeatable() {
        if (!repeatable) {
            return false;
        }

        IntervalRequirement ir = (IntervalRequirement) startReqs.get(QuestRequirementType.INTERVAL);
        return ir.getInterval() < HOURS.toMillis(GameConfig.getServerLong("quest_point_repeatable_interval"));
    }

    public boolean canStartQuestByStatus(Character chr) {
        QuestInfo mqs = chr.getQuest(this);
        return !(!mqs.getStatus().equals(QuestStatus.NOT_STARTED) && !(mqs.getStatus().equals(QuestStatus.COMPLETED) && repeatable));
    }

    public boolean canQuestByInfoProgress(Character chr) {
        QuestInfo mqs = chr.getQuest(this);
        List<String> ix = mqs.getInfoEx();
        if (!ix.isEmpty()) {
            int questid = mqs.getQuestID();
            int infoNumber = mqs.getInfoNumber();
            if (infoNumber <= 0) {
                infoNumber = questid;  // on default infoNumber mimics questid
            }

            int ixSize = ix.size();
            for (int i = 0; i < ixSize; i++) {
                String progress = chr.getAbstractPlayerInteraction().getQuestProgress(infoNumber, i);
                String ixProgress = ix.get(i);

                if (!progress.contentEquals(ixProgress)) {
                    return false;
                }
            }
        }

        return true;
    }

    public boolean canStart(Character chr, int npcid) {
        if (!canStartQuestByStatus(chr)) {
            return false;
        }

        for (AbstractQuestRequirement r : startReqs.values()) {
            if (!r.check(chr, npcid)) {
                return false;
            }
        }

        return canQuestByInfoProgress(chr);
    }

    public boolean canComplete(Character chr, Integer npcid) {
        QuestInfo mqs = chr.getQuest(this);
        if (!mqs.getStatus().equals(QuestStatus.STARTED)) {
            return false;
        }

        for (AbstractQuestRequirement r : completeReqs.values()) {
            if (!r.check(chr, npcid)) {
                return false;
            }
        }

        return canQuestByInfoProgress(chr);
    }

    public void start(Character chr, int npc) {
        if (autoStart || canStart(chr, npc)) {
            Collection<AbstractQuestAction> acts = startActs.values();
            for (AbstractQuestAction a : acts) {
                if (!a.check(chr, null)) { // would null be good ?
                    return;
                }
            }
            for (AbstractQuestAction a : acts) {
                a.run(chr, null);
            }
            forceStart(chr, npc);
        }
    }

    public void complete(Character chr, int npc) {
        complete(chr, npc, null);
    }

    public void complete(Character chr, int npc, Integer selection) {
        if (autoPreComplete || canComplete(chr, npc)) {
            Collection<AbstractQuestAction> acts = completeActs.values();
            for (AbstractQuestAction a : acts) {
                if (!a.check(chr, selection)) {
                    return;
                }
            }
            forceComplete(chr, npc);
            for (AbstractQuestAction a : acts) {
                a.run(chr, selection);
            }
            if (!this.hasNextQuestAction()) {
                chr.announceUpdateQuest(DelayedQuestUpdate.INFO, chr.getQuest(this));
            }
        }
    }

    public void reset(Character chr) {
        QuestInfo newStatus = new QuestInfo(this, QuestStatus.NOT_STARTED);
        chr.updateQuestStatus(newStatus);
    }

    public boolean forfeit(Character chr) {
        if (!chr.getQuest(this).getStatus().equals(QuestStatus.STARTED)) {
            return false;
        }
        if (timeLimit > 0) {
            chr.sendPacket(PacketCreator.removeQuestTimeLimit((short) id));
        }
        QuestInfo newStatus = new QuestInfo(this, QuestStatus.NOT_STARTED);
        newStatus.setForfeited(chr.getQuest(this).getForfeited() + 1);
        chr.updateQuestStatus(newStatus);
        return true;
    }

    public boolean forceStart(Character chr, int npc) {
        QuestInfo newStatus = new QuestInfo(this, QuestStatus.STARTED, npc);

        QuestInfo oldStatus = chr.getQuest(this.getId());
        for (Entry<Integer, String> e : oldStatus.getProgress().entrySet()) {
            newStatus.setProgress(e.getKey(), e.getValue());
        }

        if (id / 100 == 35 && GameConfig.getServerInt("tot_mob_quest_requirement") > 0) {
            int setProg = 999 - Math.min(999, GameConfig.getServerInt("tot_mob_quest_requirement"));

            for (Integer pid : newStatus.getProgress().keySet()) {
                if (pid >= 8200000 && pid <= 8200012) {
                    String pr = StringUtil.getLeftPaddedStr(Integer.toString(setProg), '0', 3);
                    newStatus.setProgress(pid, pr);
                }
            }
        }

        newStatus.setForfeited(chr.getQuest(this).getForfeited());
        newStatus.setCompleted(chr.getQuest(this).getCompleted());

        if (timeLimit > 0) {
            newStatus.setExpirationTime(System.currentTimeMillis() + SECONDS.toMillis(timeLimit));
            chr.questTimeLimit(this, timeLimit);
        }
        if (timeLimit2 > 0) {
            newStatus.setExpirationTime(System.currentTimeMillis() + SECONDS.toMillis(timeLimit2));
            chr.questTimeLimit2(this, newStatus.getExpirationTime());
        }

        chr.updateQuestStatus(newStatus);

        return true;
    }

    public boolean forceComplete(Character chr, int npc) {
        if (timeLimit > 0) {
            chr.sendPacket(PacketCreator.removeQuestTimeLimit((short) id));
        }

        QuestInfo newStatus = new QuestInfo(this, QuestStatus.COMPLETED, npc);
        newStatus.setForfeited(chr.getQuest(this).getForfeited());
        newStatus.setCompleted(chr.getQuest(this).getCompleted());
        newStatus.setCompletionTime(System.currentTimeMillis());
        chr.updateQuestStatus(newStatus);

        chr.sendPacket(PacketCreator.showSpecialEffect(9)); // Quest completion
        chr.getMapRef().broadcastMessage(chr.ref(), PacketCreator.showForeignEffect(chr.getId(), 9), false); //use 9 instead of 12 for both
        return true;
    }

    public int getId() {
        return id;
    }

    public List<Integer> getRelevantMobs() {
        return relevantMobs;
    }

    public int getStartItemAmountNeeded(int itemid) {
        AbstractQuestRequirement req = startReqs.get(QuestRequirementType.ITEM);
        if (req == null) {
            return Integer.MIN_VALUE;
        }

        ItemRequirement ireq = (ItemRequirement) req;
        return ireq.getItemAmountNeeded(itemid, false);
    }

    public int getCompleteItemAmountNeeded(int itemid) {
        AbstractQuestRequirement req = completeReqs.get(QuestRequirementType.ITEM);
        if (req == null) {
            return Integer.MAX_VALUE;
        }

        ItemRequirement ireq = (ItemRequirement) req;
        return ireq.getItemAmountNeeded(itemid, true);
    }

    public int getMobAmountNeeded(int mid) {
        AbstractQuestRequirement req = completeReqs.get(QuestRequirementType.MOB);
        if (req == null) {
            return 0;
        }

        MobRequirement mreq = (MobRequirement) req;

        return mreq.getRequiredMobCount(mid);
    }

    public short getInfoNumber(QuestStatus qs) {
        boolean checkEnd = qs.equals(QuestStatus.STARTED);
        Map<QuestRequirementType, AbstractQuestRequirement> reqs = !checkEnd ? startReqs : completeReqs;

        AbstractQuestRequirement req = reqs.get(QuestRequirementType.INFO_NUMBER);
        if (req != null) {
            InfoNumberRequirement inReq = (InfoNumberRequirement) req;
            return inReq.getInfoNumber();
        } else {
            return 0;
        }
    }

    public String getInfoEx(QuestStatus qs, int index) {
        boolean checkEnd = qs.equals(QuestStatus.STARTED);
        Map<QuestRequirementType, AbstractQuestRequirement> reqs = !checkEnd ? startReqs : completeReqs;
        try {
            AbstractQuestRequirement req = reqs.get(QuestRequirementType.INFO_EX);
            InfoExRequirement ixReq = (InfoExRequirement) req;
            return ixReq.getInfo().get(index);
        } catch (Exception e) {
            return "";
        }
    }

    public List<String> getInfoEx(QuestStatus qs) {
        boolean checkEnd = qs.equals(QuestStatus.STARTED);
        Map<QuestRequirementType, AbstractQuestRequirement> reqs = !checkEnd ? startReqs : completeReqs;
        try {
            AbstractQuestRequirement req = reqs.get(QuestRequirementType.INFO_EX);
            InfoExRequirement ixReq = (InfoExRequirement) req;
            return ixReq.getInfo();
        } catch (Exception e) {
            return new LinkedList<>();
        }
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public static void clearCache(int quest) {
        quests.remove(quest);
    }

    public static void clearCache() {
        quests.clear();
    }

    private AbstractQuestRequirement getRequirement(QuestRequirementType type, Data data) {
        AbstractQuestRequirement ret = null;
        switch (type) {
            case END_DATE:
                ret = new EndDateRequirement(this, data);
                break;
            case JOB:
                ret = new JobRequirement(this, data);
                break;
            case QUEST:
                ret = new QuestRequirement(this, data);
                break;
            case FIELD_ENTER:
                ret = new FieldEnterRequirement(this, data);
                break;
            case INFO_NUMBER:
                ret = new InfoNumberRequirement(this, data);
                break;
            case INFO_EX:
                ret = new InfoExRequirement(this, data);
                break;
            case INTERVAL:
                ret = new IntervalRequirement(this, data);
                break;
            case COMPLETED_QUEST:
                ret = new CompletedQuestRequirement(this, data);
                break;
            case ITEM:
                ret = new ItemRequirement(this, data);
                break;
            case MAX_LEVEL:
                ret = new MaxLevelRequirement(this, data);
                break;
            case MESO:
                ret = new MesoRequirement(this, data);
                break;
            case MIN_LEVEL:
                ret = new MinLevelRequirement(this, data);
                break;
            case MIN_PET_TAMENESS:
                ret = new MinTamenessRequirement(this, data);
                break;
            case MOB:
                ret = new MobRequirement(this, data);
                break;
            case MONSTER_BOOK:
                ret = new MonsterBookCountRequirement(this, data);
                break;
            case NPC:
                ret = new NpcRequirement(this, data);
                break;
            case PET:
                ret = new PetRequirement(this, data);
                break;
            case BUFF:
                ret = new BuffRequirement(this, data);
                break;
            case EXCEPT_BUFF:
                ret = new BuffExceptRequirement(this, data);
                break;
            case SCRIPT:
                ret = new ScriptRequirement(this, data);
                break;
            case NORMAL_AUTO_START:
            case START:
            case END:
                break;
            default:
                //FilePrinter.printError(FilePrinter.EXCEPTION_CAUGHT, "Unhandled Requirement Type: " + type.toString() + " QuestID: " + this.getId());
                break;
        }
        return ret;
    }

    private AbstractQuestAction getAction(QuestActionType type, Data data) {
        AbstractQuestAction ret = null;
        switch (type) {
            case BUFF:
                ret = new BuffAction(this, data);
                break;
            case EXP:
                ret = new ExpAction(this, data);
                break;
            case FAME:
                ret = new FameAction(this, data);
                break;
            case ITEM:
                ret = new ItemAction(this, data);
                break;
            case MESO:
                ret = new MesoAction(this, data);
                break;
            case NEXTQUEST:
                ret = new NextQuestAction(this, data);
                break;
            case PETSKILL:
                ret = new PetSkillAction(this, data);
                break;
            case QUEST:
                ret = new QuestAction(this, data);
                break;
            case SKILL:
                ret = new SkillAction(this, data);
                break;
            case PETTAMENESS:
                ret = new PetTamenessAction(this, data);
                break;
            case PETSPEED:
                ret = new PetSpeedAction(this, data);
                break;
            case INFO:
                ret = new InfoAction(this, data);
                break;
            default:
                //FilePrinter.printError(FilePrinter.EXCEPTION_CAUGHT, "Unhandled Action Type: " + type.toString() + " QuestID: " + this.getId());
                break;
        }
        return ret;
    }

    public boolean restoreLostItem(Character chr, int itemid) {
        if (chr.getQuest(this).getStatus().equals(QuestStatus.STARTED)) {
            ItemAction itemAct = (ItemAction) startActs.get(QuestActionType.ITEM);
            if (itemAct != null) {
                return itemAct.restoreLostItem(chr, itemid);
            }
        }

        return false;
    }

    public int getMedalRequirement() {
        Integer medalid = medals.get(id);
        return medalid != null ? medalid : -1;
    }

    public int getNpcRequirement(boolean checkEnd) {
        Map<QuestRequirementType, AbstractQuestRequirement> reqs = !checkEnd ? startReqs : completeReqs;
        AbstractQuestRequirement mqr = reqs.get(QuestRequirementType.NPC);
        if (mqr != null) {
            return ((NpcRequirement) mqr).get();
        } else {
            return -1;
        }
    }

    /** WZ Check.img 指定的脚本入口名（doc/13 §15）：接取侧 startscript / 完成侧 endscript，未声明返回 null */
    public String getQuestScriptName(boolean checkEnd) {
        Map<QuestRequirementType, AbstractQuestRequirement> reqs = !checkEnd ? startReqs : completeReqs;
        AbstractQuestRequirement mqr = reqs.get(QuestRequirementType.SCRIPT);
        return mqr instanceof ScriptRequirement sr ? sr.get() : null;
    }

    public boolean hasScriptRequirement(boolean checkEnd) {
        Map<QuestRequirementType, AbstractQuestRequirement> reqs = !checkEnd ? startReqs : completeReqs;
        AbstractQuestRequirement mqr = reqs.get(QuestRequirementType.SCRIPT);

        if (mqr instanceof ScriptRequirement sr) {
            String name = sr.get();
            return name != null && !name.isEmpty();
        } else {
            return false;
        }
    }

    public boolean hasNextQuestAction() {
        Map<QuestActionType, AbstractQuestAction> acts = completeActs;
        AbstractQuestAction mqa = acts.get(QuestActionType.NEXTQUEST);

        return mqa != null;
    }

    public String getName() {
        return name;
    }

    public String getParentName() {
        return parent;
    }

    public static boolean isExploitableQuest(int questid) {
        return exploitableQuests.contains(questid);
    }

    public static List<QuestWz> getMatchedQuests(String search) {
        List<QuestWz> ret = new LinkedList<>();

        search = search.toLowerCase();
        for (QuestWz mq : quests.values()) {
            if (mq.name.toLowerCase().contains(search) || mq.parent.toLowerCase().contains(search)) {
                ret.add(mq);
            }
        }

        return ret;
    }

    public static void loadAllQuests() {
        final Map<Integer, QuestWz> loadedQuests = new ConcurrentHashMap<>();
        final Map<Integer, Integer> loadedInfoNumberQuests = new HashMap<>();

        for (Data quest : questInfo.getChildren()) {
            int questID = Integer.parseInt(quest.getName());

            QuestWz q = new QuestWz(questID);
            loadedQuests.put(questID, q);

            int infoNumber;

            infoNumber = q.getInfoNumber(QuestStatus.STARTED);
            if (infoNumber > 0) {
                loadedInfoNumberQuests.put(infoNumber, questID);
            }

            infoNumber = q.getInfoNumber(QuestStatus.COMPLETED);
            if (infoNumber > 0) {
                loadedInfoNumberQuests.put(infoNumber, questID);
            }
        }

        QuestWz.quests = loadedQuests;
        QuestWz.infoNumberQuests = loadedInfoNumberQuests;
    }

    public void expireQuest(Character chr) {
        if (forfeit(chr)) {
            chr.sendPacket(PacketCreator.questExpire((short) getId()));
        }
    }
}
