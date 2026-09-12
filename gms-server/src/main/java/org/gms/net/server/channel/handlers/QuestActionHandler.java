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
package org.gms.net.server.channel.handlers;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.constants.id.MapId;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.scripting.quest.esm.EsmQuests;
import org.gms.server.life.NPC;
import org.gms.server.quest.Quest;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;

/**
 * @author Matze
 */
public final class QuestActionHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(QuestActionHandler.class);

    @Override
    public boolean queued() {
        // strand 迁移 M2-batch3（范围：quest.log 实测触发的 case 1 接取 / case 2 完成直连路径，
        // doc/13）：任务状态/背包/exp 均为 chr actor 自身状态；出 actor 的仅 forceComplete 的
        // showForeignEffect 地图广播（他人流直调 = 容忍模式）。
        // 未触发分支（case 0/3/4/5、脚本子路径 QuestScriptManager.start/end、带 selection 完成、
        // 白精华特例）随 queued 化一并上 strand，但正确性审计增量补做；其共享存储
        // （QuestScriptManager 的 HashMap）存在存量竞态，脚本分支迁移时一并修复。
        return true;
    }
    private static final short LOST_WHITE_ESSENCE_QUEST = 4522;
    private static final short CAPTAIN_LATANICA_RETURN_QUEST = 4523;
    private static final int WHITE_ESSENCE = 4000381;

    private static void sendNpcOk(Client c, int npc, String message) {
        c.sendPacket(PacketCreator.getNPCTalk(npc, (byte) 0, message, "00 00", (byte) 0));
    }

    // isNpcNearby thanks to GabrielSin
    private static boolean isNpcNearby(InPacket p, Character player, Quest quest, int npcId) {
        Point playerP;
        Point pos = player.getPosition();

        if (p.available() >= 4) {
            playerP = new Point(p.readShort(), p.readShort());
            if (playerP.distance(pos) > 1000) {     // thanks Darter (YungMoozi) for reporting unchecked player position
                playerP = pos;
            }
        } else {
            playerP = pos;
        }

        if (!quest.isAutoStart() && !quest.isAutoComplete()) {
            NPC npc = player.getMap().getNPCById(npcId);
            if (npc == null) {
                log.warn("QUEST_ACTION 拒绝: 任务 {} 的 NPC {} 不在地图 {} 上, 玩家 {}",
                        quest.getId(), npcId, player.getMapId(), player.getName());
                return false;
            }

            Point npcP = npc.getPosition();
            if (Math.abs(npcP.getX() - playerP.getX()) > 1200 || Math.abs(npcP.getY() - playerP.getY()) > 800) {
                log.warn("QUEST_ACTION 拒绝: 任务 {} 的 NPC {} 距玩家 {} 过远 (npc=({},{}) player=({},{})) 地图 {}",
                        quest.getId(), npcId, player.getName(), npcP.x, npcP.y, playerP.x, playerP.y, player.getMapId());
                player.dropMessage(5, I18nUtil.getMessage("QuestActionHandler.isNpcNearby.message1"));
                return false;
            }
        }

        return true;
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        byte action = p.readByte();
        short questid = p.readShort();
        Character player = c.getPlayer();
        Quest quest = Quest.getInstance(questid);
        if (player.getMapId() == MapId.JAIL) {   //监狱地图不可使用任务脚本
            player.dropMessage(1,I18nUtil.getMessage("ActionHandler.map.message1"));
            return;
        }
        switch (action) {
            case 0: // Restore lost item, Credits Darter ( Rajan )
                p.readInt();
                int itemid = p.readInt();
                quest.restoreLostItem(player, itemid);
                break;
            case 1: { // Start Quest
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc)) {
                    return;
                }
                if (quest.canStart(player, npc)) {
                    boolean success = QuestScriptManager.getInstance().checkFunctionExists(c, questid, npc, "start");
                    boolean hasScriptRequirement = quest.hasScriptRequirement(false);
                    if (hasScriptRequirement && success) {
                        QuestScriptManager.getInstance().start(c, questid, npc);
                    } else {
                        quest.start(player, npc);
                    }
                } else if (questid == LOST_WHITE_ESSENCE_QUEST && player.haveItem(WHITE_ESSENCE)) {
                    sendNpcOk(c, npc, I18nUtil.getMessage("QuestActionHandler.hasWhiteEssence.message1"));
                } else if (questid == CAPTAIN_LATANICA_RETURN_QUEST && player.haveItem(WHITE_ESSENCE)) {
                    sendNpcOk(c, npc, I18nUtil.getMessage("QuestActionHandler.hasWhiteEssenceForLatanica.message1"));
                } else {
                    log.warn("QUEST_ACTION 拒绝: 玩家 {} 不满足任务 {} 的接取条件 (等级/道具/NPC {}), 地图 {}",
                            player.getName(), questid, npc, player.getMapId());
                }
                break;
            }
            case 2: { // Complete Quest
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc)) {
                    return;
                }
                if (quest.canComplete(player, npc)) {
                    boolean success = QuestScriptManager.getInstance().checkFunctionExists(c, questid, npc, "end");
                    boolean hasScriptRequirement = quest.hasScriptRequirement(true);
                    if (hasScriptRequirement && success) {
                        QuestScriptManager.getInstance().end(c, questid, npc);
                    } else {
                        if (p.available() >= 2) {
                            int selection = p.readShort();
                            quest.complete(player, npc, selection);
                        } else {
                            quest.complete(player, npc);
                        }
                    }
                } else {
                    log.warn("QUEST_ACTION 拒绝: 玩家 {} 不满足任务 {} 的完成条件 (等级/道具/NPC {}), 地图 {}",
                            player.getName(), questid, npc, player.getMapId());
                }
                break;
            }
            case 3: // forfeit quest
                quest.forfeit(player);
                break;
            case 4: { // scripted start quest
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc)) {
                    return;
                }
                if (quest.canStart(player, npc)) {
                    String entry = quest.getQuestScriptName(false);
                    if (entry != null && EsmQuests.exists(questid)) {
                        EsmQuests.start(player, questid, npc, entry);   // ESM 新系统：入口名来自 WZ startscript（doc/13 §15）
                    } else if (entry == null) {
                        QuestScriptManager.getInstance().start(c, questid, npc);
                    }
                }
                break;
            }
            case 5: { // scripted end quests
                int npc = p.readInt();
                if (!isNpcNearby(p, player, quest, npc)) {
                    return;
                }
                if (quest.canComplete(player, npc)) {
                    String entry = quest.getQuestScriptName(true);
                    if (entry != null && EsmQuests.exists(questid)) {
                        EsmQuests.end(player, questid, npc, entry);     // ESM 新系统：入口名来自 WZ endscript
                    } else if (entry == null) {
                        QuestScriptManager.getInstance().end(c, questid, npc);
                    }
                } else {
                    log.warn("QUEST_ACTION 拒绝: 玩家 {} 不满足任务 {} 的脚本完成条件 (NPC {}), 地图 {}",
                            player.getName(), questid, npc, player.getMapId());
                }
                break;
            }
        }
    }
}
