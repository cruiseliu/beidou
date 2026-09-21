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
import org.gms.constants.game.DelayedQuestUpdate;
import org.gms.config.GameConfig;
import org.gms.util.PacketCreator;
import org.gms.util.StringUtil;
import org.gms.client.quest.actions.AbstractQuestAction;
import org.gms.client.quest.actions.ItemAction;
import org.gms.client.quest.requirements.AbstractQuestRequirement;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Matze
 */
public class QuestInfo {

    private final int questID;
    private QuestStatus status;
    //private boolean updated;   //maybe this can be of use for someone?
    private final Map<Integer, String> progress = new LinkedHashMap<>();
    private final List<Integer> medalProgress = new LinkedList<>();
    private int npc;
    private long completionTime, expirationTime;
    private int forfeited = 0, completed = 0;
    private String customData;

    public QuestInfo(int questId) {
        this.questID = questId;
        this.setStatus(QuestStatus.NOT_STARTED);
        this.completionTime = 0;
        this.expirationTime = 0;
    }

    public QuestInfo(QuestWz quest, QuestStatus status) {
        this.questID = quest.getId();
        this.setStatus(status);
        this.completionTime = System.currentTimeMillis();
        this.expirationTime = 0;
        //this.updated = true;
        if (status == QuestStatus.STARTED) {
            registerMobs();
        }
    }

    public QuestInfo(QuestWz quest, QuestStatus status, int npc) {
        this.questID = quest.getId();
        this.setStatus(status);
        this.setNpc(npc);
        this.completionTime = System.currentTimeMillis();
        this.expirationTime = 0;
        //this.updated = true;
        if (status == QuestStatus.STARTED) {
            registerMobs();
        }
    }

    public QuestWz getQuest() {
        return QuestWz.getInstance(questID);
    }

    public int getQuestID() {
        return questID;
    }

    public QuestStatus getStatus() {
        return status;
    }

    public final void setStatus(QuestStatus status) {
        this.status = status;
    }
    
    /*
    public boolean wasUpdated() {
        return updated;
    }
    
    private void setUpdated() {
        this.updated = true;
    }
    
    public void resetUpdated() {
        this.updated = false;
    }
    */

    public int getNpc() {
        return npc;
    }

    public final void setNpc(int npc) {
        this.npc = npc;
    }

    private void registerMobs() {
        for (int i : QuestWz.getInstance(questID).getRelevantMobs()) {
            progress.put(i, "000");
        }
        //this.setUpdated();
    }

    public boolean addMedalMap(int mapid) {
        if (medalProgress.contains(mapid)) {
            return false;
        }
        medalProgress.add(mapid);
        //this.setUpdated();
        return true;
    }

    public int getMedalProgress() {
        return medalProgress.size();
    }

    public List<Integer> getMedalMaps() {
        return medalProgress;
    }

    public boolean progress(int id) {
        String currentStr = progress.get(id);
        if (currentStr == null) {
            return false;
        }

        int current = Integer.parseInt(currentStr);
        if (current >= this.getQuest().getMobAmountNeeded(id)) {
            return false;
        }

        String str = StringUtil.getLeftPaddedStr(Integer.toString(++current), '0', 3);
        progress.put(id, str);
        //this.setUpdated();
        return true;
    }

    public void setProgress(int id, String pr) {
        progress.put(id, pr);
        //this.setUpdated();
    }

    public boolean madeProgress() {
        return progress.size() > 0;
    }

    public String getProgress(int id) {
        String ret = progress.get(id);
        if (ret == null) {
            return "";
        } else {
            return ret;
        }
    }

    public void resetProgress(int id) {
        setProgress(id, "000");
    }

    public void resetAllProgress() {
        for (Map.Entry<Integer, String> entry : progress.entrySet()) {
            setProgress(entry.getKey(), "000");
        }
    }

    public Map<Integer, String> getProgress() {
        return Collections.unmodifiableMap(progress);
    }

    public short getInfoNumber() {
        QuestWz q = this.getQuest();
        QuestStatus s = this.getStatus();

        return q.getInfoNumber(s);
    }

    public String getInfoEx(int index) {
        QuestWz q = this.getQuest();
        QuestStatus s = this.getStatus();

        return q.getInfoEx(s, index);
    }

    public List<String> getInfoEx() {
        QuestWz q = this.getQuest();
        QuestStatus s = this.getStatus();

        return q.getInfoEx(s);
    }

    public long getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(long completionTime) {
        this.completionTime = completionTime;
    }

    public long getExpirationTime() {
        return expirationTime;
    }

    public void setExpirationTime(long expirationTime) {
        this.expirationTime = expirationTime;
    }

    public int getForfeited() {
        return forfeited;
    }

    public int getCompleted() {
        return completed;
    }

    public void setForfeited(int forfeited) {
        if (forfeited >= this.forfeited) {
            this.forfeited = forfeited;
        } else {
            throw new IllegalArgumentException("Can't set forfeits to something lower than before.");
        }
    }

    public void setCompleted(int completed) {
        if (completed >= this.completed) {
            this.completed = completed;
        } else {
            throw new IllegalArgumentException("Can't set completes to something lower than before.");
        }
    }

    public final void setCustomData(final String customData) {
        this.customData = customData;
    }

    public final String getCustomData() {
        return customData;
    }

    public String getProgressData() {
        StringBuilder str = new StringBuilder();
        for (String ps : progress.values()) {
            str.append(ps);
        }
        return str.toString();
    }

    // ── 动态操作（任务域裁定：状态迁移在本实例上就地生效，QuestWz 只读静态定义）──
    // 就地变异等价复刻旧"换新对象顶替 map 条目"形态的净状态效果（progress 清空/重播种、
    // forfeited/completed 归复规则逐项保留）；npcUpdateQuests 延迟队列持有本实例引用，
    // NPC 对话期间同任务的连发更新在冲刷时呈现终态（帧数与末帧内容不变，中间帧为幂等
    // 状态集被终态覆盖，客户端净状态一致）。

    private boolean canStartQuestByStatus(Character chr) {
        return !(!status.equals(QuestStatus.NOT_STARTED) && !(status.equals(QuestStatus.COMPLETED) && getQuest().isRepeatable()));
    }

    private boolean canQuestByInfoProgress(Character chr) {
        List<String> ix = getInfoEx();
        if (!ix.isEmpty()) {
            int infoNumber = getInfoNumber();
            if (infoNumber <= 0) {
                infoNumber = questID;  // on default infoNumber mimics questid
            }

            for (int i = 0; i < ix.size(); i++) {
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

        for (AbstractQuestRequirement r : getQuest().getStartReqs().values()) {
            if (!r.check(chr, npcid)) {
                return false;
            }
        }

        return canQuestByInfoProgress(chr);
    }

    public boolean canComplete(Character chr, Integer npcid) {
        if (!status.equals(QuestStatus.STARTED)) {
            return false;
        }

        for (AbstractQuestRequirement r : getQuest().getCompleteReqs().values()) {
            if (!r.check(chr, npcid)) {
                return false;
            }
        }

        return canQuestByInfoProgress(chr);
    }

    public void start(Character chr, int npc) {
        if (getQuest().isAutoStart() || canStart(chr, npc)) {
            Collection<AbstractQuestAction> acts = getQuest().getStartActs().values();
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
        if (getQuest().isAutoPreComplete() || canComplete(chr, npc)) {
            Collection<AbstractQuestAction> acts = getQuest().getCompleteActs().values();
            for (AbstractQuestAction a : acts) {
                if (!a.check(chr, selection)) {
                    return;
                }
            }
            forceComplete(chr, npc);
            for (AbstractQuestAction a : acts) {
                a.run(chr, selection);
            }
            if (!getQuest().hasNextQuestAction()) {
                chr.announceUpdateQuest(DelayedQuestUpdate.INFO, this);
            }
        }
    }

    public void reset(Character chr) {
        // 复刻旧"新对象"全字段复位（completionTime=now 为历史行为原样保留；
        // 直接字段赋值绕过 setForfeited/setCompleted 的单调护栏——归零即旧语义）
        status = QuestStatus.NOT_STARTED;
        npc = 0;
        completionTime = System.currentTimeMillis();
        expirationTime = 0;
        forfeited = 0;
        completed = 0;
        progress.clear();
        chr.updateQuestStatus(this);
    }

    public boolean forfeit(Character chr) {
        if (!status.equals(QuestStatus.STARTED)) {
            return false;
        }
        if (getQuest().getTimeLimit() > 0) {
            chr.sendPacket(PacketCreator.removeQuestTimeLimit((short) questID));
        }
        status = QuestStatus.NOT_STARTED;
        npc = 0;
        completionTime = System.currentTimeMillis();
        expirationTime = 0;
        forfeited = this.forfeited + 1;
        completed = 0;
        progress.clear();
        chr.updateQuestStatus(this);
        return true;
    }

    public boolean forceStart(Character chr, int npc) {
        Map<Integer, String> oldProgress = new LinkedHashMap<>(progress);

        setStatus(QuestStatus.STARTED);
        setNpc(npc);
        completionTime = System.currentTimeMillis();
        progress.clear();
        registerMobs();
        for (Map.Entry<Integer, String> e : oldProgress.entrySet()) {
            progress.put(e.getKey(), e.getValue());
        }

        if (questID / 100 == 35 && GameConfig.getServerInt("tot_mob_quest_requirement") > 0) {
            int setProg = 999 - Math.min(999, GameConfig.getServerInt("tot_mob_quest_requirement"));

            for (Integer pid : progress.keySet()) {
                if (pid >= 8200000 && pid <= 8200012) {
                    progress.put(pid, StringUtil.getLeftPaddedStr(Integer.toString(setProg), '0', 3));
                }
            }
        }

        if (getQuest().getTimeLimit() > 0) {
            expirationTime = System.currentTimeMillis() + SECONDS.toMillis(getQuest().getTimeLimit());
            chr.questTimeLimit(questID, getQuest().getTimeLimit());
        }
        if (getQuest().getTimeLimit2() > 0) {
            expirationTime = System.currentTimeMillis() + SECONDS.toMillis(getQuest().getTimeLimit2());
            chr.questTimeLimit2(questID, expirationTime);
        }

        chr.updateQuestStatus(this);

        return true;
    }

    public boolean forceComplete(Character chr, int npc) {
        if (getQuest().getTimeLimit() > 0) {
            chr.sendPacket(PacketCreator.removeQuestTimeLimit((short) questID));
        }

        setStatus(QuestStatus.COMPLETED);
        setNpc(npc);
        completionTime = System.currentTimeMillis();
        progress.clear();   // 旧实现 COMPLETED 换新对象不带 progress；重跑种子由 forceStart 重播
        chr.updateQuestStatus(this);

        chr.sendPacket(PacketCreator.showSpecialEffect(9)); // Quest completion
        chr.getMapRef().broadcastMessage(chr.ref(), PacketCreator.showForeignEffect(chr.getId(), 9), false); //use 9 instead of 12 for both
        return true;
    }

    public boolean restoreLostItem(Character chr, int itemid) {
        if (status.equals(QuestStatus.STARTED)) {
            ItemAction itemAct = (ItemAction) getQuest().getStartActs().get(QuestActionType.ITEM);
            if (itemAct != null) {
                return itemAct.restoreLostItem(chr, itemid);
            }
        }

        return false;
    }

    public void expireQuest(Character chr) {
        if (forfeit(chr)) {
            chr.sendPacket(PacketCreator.questExpire((short) questID));
        }
    }
}
