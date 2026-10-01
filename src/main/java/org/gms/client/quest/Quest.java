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

import org.gms.client.Player;
import org.gms.client.character.Character;
import org.gms.model.json.QuestData;
import org.gms.scripting.quest.esm.EsmQuests;
import org.gms.util.AssertUtil;
import org.gms.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.client.quest.actions.AbstractQuestAction;
import org.gms.client.quest.actions.ItemAction;
import org.gms.client.quest.requirements.AbstractQuestRequirement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author Matze
 */
public class Quest {
    private static final Logger log = LoggerFactory.getLogger(Quest.class);

    private final int id;
    private QuestStatus status;
    private final Map<Integer, String> progress = new LinkedHashMap<>();
    private final List<Integer> medalProgress = new LinkedList<>();
    private int npc;
    private long completionTime;

    private final Character chr;
    private final QuestWz wz;

    private final int NEVER = 0;

    public Quest(int questId) {
        chr = Player.require("quest").character();
        wz = QuestWz.getInstance(questId);
        this.id = questId;
        this.status = QuestStatus.NOT_STARTED;
        this.completionTime = NEVER;
    }

    private Quest(QuestWz wz, QuestStatus status, Character owner) {
        this.chr = owner;
        this.id = wz.getId();
        this.wz = wz;
        this.status = status;
        this.completionTime = System.currentTimeMillis();
        if (status == QuestStatus.STARTED) {
            registerMobs();
        }
    }

    /**
     * 装载恢复档：charlist 预览 / 选角装载路径（先于会话开始，无 actor 可达，装载线程
     * 不在 player strand）——owner 显式传入，不设 strand 护栏。进场后的动态操作仍受
     * player strand 纪律约束，本豁免仅覆盖装载。
     */
    public static Quest fromData(Character owner, QuestData data) {
        Quest qs = new Quest(QuestWz.getInstance(data.quest), QuestStatus.fromValue(data.status), owner);
        qs.completionTime = data.completionTime;
        if (data.progress != null) {
            qs.progress.putAll(data.progress);   // 覆盖 registerMobs 播种（与旧 setProgress 逐项 put 同序）
        }
        if (data.medalMaps != null) {
            for (int mapid : data.medalMaps) {
                qs.addMedalMap(mapid);   // 走实体去重路径
            }
        }
        return qs;
    }

    /** 持久化快照（仿 Pet.toData；进度/探索图防御性拷贝，空集合留 null 不落库） */
    public QuestData toData() {
        QuestData data = new QuestData();
        data.quest = id;
        data.status = status.getValue();
        data.completionTime = completionTime;
        if (!progress.isEmpty()) {
            data.progress = new LinkedHashMap<>(progress);
        }
        if (!medalProgress.isEmpty()) {
            data.medalMaps = new ArrayList<>(medalProgress);
        }
        return data;
    }

    public int getId() {
        return id;
    }

    public QuestStatus getStatus() {
        return status;
    }

       public int getNpc() {
        return npc;
    }

    private void registerMobs() {
        for (int i : wz.getRelevantMobs()) {
            progress.put(i, "000");
        }
    }

    public boolean addMedalMap(int mapid) {
        if (medalProgress.contains(mapid)) {
            return false;
        }
        medalProgress.add(mapid);
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
        if (current >= wz.getMobAmountNeeded(id)) {
            return false;
        }

        String str = StringUtil.getLeftPaddedStr(Integer.toString(++current), '0', 3);
        progress.put(id, str);
        return true;
    }

    public void setProgress(int id, String pr) {
        progress.put(id, pr);
    }

    public String getProgress(int id) {
        String ret = progress.get(id);
        if (ret == null) {
            return "";
        } else {
            return ret;
        }
    }

    public Map<Integer, String> getProgress() {
        return Collections.unmodifiableMap(progress);
    }

    public int getInfoNumber() {
        return wz.getInfoNumber(status);
    }

    /**
     * infoNumber 关联任务（客户端任务面板的进度显示绑在该任务条目上）。
     * infoNumber &lt;= 0 返回 null；经 getQuestNAdd 取用——不存在则自动建项
     * （NOT_STARTED 空进度，旧接取/同步路径原样保留的副作用）。
     */
    public Quest getInfo() {
        int infoNumber = getInfoNumber();
        return infoNumber > 0 ? chr.getQuestNAdd(infoNumber) : null;
    }

    public String getInfoEx(int index) {
        return wz.getInfoEx(status, index);
    }

    public List<String> getInfoEx() {
        return wz.getInfoEx(status);
    }

    public long getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(long completionTime) {
        this.completionTime = completionTime;
    }

    // ── 动态操作（任务域裁定：状态迁移在本实例上就地生效，QuestWz 只读静态定义）──
    // 就地变异等价复刻旧"换新对象顶替 map 条目"形态的净状态效果（progress 清空/重播种、
    // forfeited/completed 归复规则逐项保留）。状态帧在变更点即时展开为语义调用（调用时点
    // 快照）；NPC 会话内的合并归 remote batch（ESM 每拍对话一段，doc/package-client.md §2），
    // 本类不经手任何发包时机。

    public void start(int npcId) {
        if (!canStart()) {
            log.warn("{} cannot start quest {}", chr, this);
            return;
        }

        Collection<AbstractQuestAction> acts = wz.getStartActs().values();
        for (AbstractQuestAction act : acts) {
            if (!act.check(chr)) {
                return;
            }
        }
        for (AbstractQuestAction act : acts) {
            act.run(chr);
        }
        forceStart(npcId);
    }

    public void runStartScript(int npcId) {
        if (!canStart()) {
            log.warn("{} cannot start quest {}", chr, this);
            return;
        }

        String script = wz.getQuestScriptName(status);
        AssertUtil.isTrue(script != null && EsmQuests.exists(id));
        EsmQuests.start(chr, id, npcId, script);
    }

    public void complete(int npcId, Integer selection) {
        if (!canComplete(chr)) {
            log.warn("{} cannot complete quest {}", chr, this);
            return;
        }

        Collection<AbstractQuestAction> acts = wz.getCompleteActs().values();
        for (AbstractQuestAction act : acts) {
            if (!act.check(chr, selection)) {
                return;
            }
        }
        forceComplete(chr, npcId);
        for (AbstractQuestAction act : acts) {
            act.run(chr, selection);
        }

        if (!wz.hasNextQuestAction()) {
            // 系列终结标记：客户端任务引导（该任务已可在交付 NPC 处交付）
            chr.getRemote().quest().questSeriesComplete(id, npc);
        }
    }

    public void runEndScript(int npcId) {
        if (!canComplete(chr)) {
            log.warn("{} cannot complete quest {}", chr, this);
            return;
        }
        
        String entry = wz.getQuestScriptName(status);
        AssertUtil.isTrue(entry != null && EsmQuests.exists(id));
        EsmQuests.end(chr, id, npcId, entry);
    }

    private boolean canStart() {
        if (status == QuestStatus.STARTED) {
            return false;
        }
        if (status == QuestStatus.COMPLETED && !wz.isRepeatable()) {
            // interval is checked by req
            return false;
        }

        for (AbstractQuestRequirement req : wz.getStartReqs().values()) {
            if (!req.check(chr)) {
                return false;
            }
        }

        return checkInfo();
    }

    public boolean canComplete(Character chr) {
        if (status != QuestStatus.STARTED) {
            return false;
        }

        for (AbstractQuestRequirement req : wz.getCompleteReqs().values()) {
            if (!req.check(chr)) {
                return false;
            }
        }

        return checkInfo();
    }

    private boolean checkInfo() {
        int infoNumber = wz.getInfoNumber(status);
        if (infoNumber <= 0) {
            return true;
        }

        String expectInfo = wz.getInfo(status);
        if (expectInfo != null) {
            String info = chr.quests().getInfo(infoNumber);
            if (!info.equals(expectInfo)) {
                return false;
            }
        }

        // FIXME: [refactor] infoEx
        return true;
    }

    public void reset(Character chr) {
        // 复刻旧"新对象"全字段复位（completionTime=now 为历史行为原样保留；
        // 直接字段赋值绕过 setForfeited/setCompleted 的单调护栏——归零即旧语义）
        status = QuestStatus.NOT_STARTED;
        npc = 0;
        completionTime = NEVER;
        progress.clear();
        chr.announceQuestState(this, false);
        if (wz.getInfoNumber(status) > 0) {
            chr.announceQuestState(this, true);
        }
        // reminder: do not reset quest progress of infoNumbers, some quests cannot backtrack
    }

    public void forfeit() {
        AssertUtil.isTrue(status == QuestStatus.STARTED);
        AssertUtil.isTrue(wz.getTimeLimit() <= 0 && wz.getTimeLimit2() <= 0);
        // if (wz.getTimeLimit() > 0) {
        //     chr.getRemote().quest().removeQuestTimeLimit(id);
        // }

        status = QuestStatus.NOT_STARTED;
        npc = 0;
        completionTime = NEVER;
        progress.clear();
        chr.announceQuestState(this, false);
        if (wz.getInfoNumber(status) > 0) {
            chr.announceQuestState(this, true);
        }
        // reminder: do not reset quest progress of infoNumbers, some quests cannot backtrack
        return;
    }

    public boolean forceStart(Character chr, int npcId) {
        AssertUtil.isTrue(chr == this.chr);
        forceStart(npcId);
        return true;
    }

    public void forceStart(int npcId) {
        Map<Integer, String> oldProgress = new LinkedHashMap<>(progress);

        status = QuestStatus.STARTED;
        this.npc = npcId;
        completionTime = NEVER;
        progress.clear();
        registerMobs();
        for (Map.Entry<Integer, String> e : oldProgress.entrySet()) {
            progress.put(e.getKey(), e.getValue());
        }

        AssertUtil.isTrue(wz.getTimeLimit() <= 0 && wz.getTimeLimit2() <= 0);
        // if (wz.getTimeLimit() > 0) {
        //     expirationTime = System.currentTimeMillis() + SECONDS.toMillis(wz.getTimeLimit());
        //     chr.questTimeLimit(id, wz.getTimeLimit());
        // }
        // if (wz.getTimeLimit2() > 0) {
        //     expirationTime = System.currentTimeMillis() + SECONDS.toMillis(wz.getTimeLimit2());
        //     chr.questTimeLimit2(id, expirationTime);
        // }

        // 状态帧 + infoNumber 关联任务同步 + NPC 交付确认：多帧合一次 QuestStartEvent
        chr.getRemote().quest().questStarted(this);
    }

    public boolean forceComplete(Character chr, int npc) {
        AssertUtil.isTrue(wz.getTimeLimit() <= 0 && wz.getTimeLimit2() <= 0);
        // if (wz.getTimeLimit() > 0) {
        //     chr.getRemote().quest().removeQuestTimeLimit(id);
        // }

        status = QuestStatus.COMPLETED;
        this.npc = npc;
        completionTime = System.currentTimeMillis();
        progress.clear();

        // 完成状态帧 + 完成演出帧：多帧合一次 QuestCompleteEvent（仅本人帧）
        chr.getRemote().quest().questComplete(this);
        // INFO 交付帧历史上在给完奖励后补发（仅非后续任务）——现由 complete() 末尾的 questSeriesComplete 承担
        chr.getMapRef().broadcastQuestComplete(chr.getId()); // 他人流演出（9 = 任务完成，语义归 map 域中继）
        return true;
    }

    public void restoreLostItem(int itemid) {
        AssertUtil.isTrue(status == QuestStatus.STARTED);
        ItemAction itemAct = (ItemAction) wz.getStartActs().get(QuestActionType.ITEM);
        AssertUtil.isTrue(itemAct != null);
        itemAct.restoreLostItem(chr, itemid);
    }

    public void expireQuest(Character chr) {
        if (status == QuestStatus.STARTED) {
            forfeit();
            chr.getRemote().quest().questExpired(id);
        }
    }
}
