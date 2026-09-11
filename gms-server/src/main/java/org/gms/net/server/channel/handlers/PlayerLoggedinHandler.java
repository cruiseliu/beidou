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

import org.gms.client.BuddyList;
import org.gms.client.BuddylistEntry;
import org.gms.client.character.Character;
import org.gms.client.CharacterNameAndId;
import org.gms.client.Client;
import org.gms.client.Family;
import org.gms.client.FamilyEntry;
import org.gms.client.Mount;
import org.gms.client.Player;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.pet.Pet;
import org.gms.client.keybind.KeyBinding;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.manager.ServerManager;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.channel.CharacterIdChannelPair;
import org.gms.server.maps.MapleMap;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.PlayerSession;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.coordinator.world.EventRecallCoordinator;
import org.gms.net.server.guild.Alliance;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.guild.GuildPackets;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.service.HpMpAlertService;
import org.gms.util.I18nUtil;
import org.gms.util.packets.WeddingPackets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.service.NoteService;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;

public final class PlayerLoggedinHandler extends AbstractPacketHandler {

    @Override
    public boolean queued() {
        return true;   // strand 迁移 M2-2a：登录/世界入口链路（charlist 装载、世界入口派发）
    }

    private static final Logger log = LoggerFactory.getLogger(PlayerLoggedinHandler.class);
    private static final Set<Integer> attemptingLoginAccounts = new HashSet<>();

    private final NoteService noteService;

    private static final HpMpAlertService hpMpAlertService = ServerManager.getApplicationContext().getBean(HpMpAlertService.class);

    public PlayerLoggedinHandler(NoteService noteService) {
        this.noteService = noteService;
    }

    private boolean tryAcquireAccount(int accId) {
        synchronized (attemptingLoginAccounts) {
            if (attemptingLoginAccounts.contains(accId)) {
                return false;
            }

            attemptingLoginAccounts.add(accId);
            return true;
        }
    }

    private void releaseAccount(int accId) {
        synchronized (attemptingLoginAccounts) {
            attemptingLoginAccounts.remove(accId);
        }
    }

    @Override
    public final boolean validateState(Client c) {
        return !c.isLoggedIn();
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {  //角色进入频道函数入口
        final int cid = p.readInt(); // TODO: investigate if this is the "client id" supplied in PacketCreator#getServerIP()
        final Server server = Server.getInstance();


        if (!c.tryacquireClient()) {
            // thanks MedicOP for assisting on concurrency protection here
            c.sendPacket(PacketCreator.getAfterLoginError(10));
        }

        try {
            World wserv = server.getWorld(c.getWorld());
            if (wserv == null) {
                c.disconnect(true, false);
                return;
            }

            Channel cserv = wserv.getChannel(c.getChannel());
            if (cserv == null) {
                c.setChannel(1);
                cserv = wserv.getChannel(c.getChannel());

                if (cserv == null) {
                    c.disconnect(true, false);
                    return;
                }
            }

            Character player = wserv.getPlayerStorage().getCharacterById(cid);

            boolean newcomer = false;
            if (player == null) {
                try {
                    player = Character.loadCharFromDB(cid, c, true);
                    newcomer = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (player == null) { //If you are still getting null here then please just uninstall the game >.>, we dont need you fucking with the logs
                    c.disconnect(true, false);
                    return;
                }
            }

            if (!server.validateCharacteridInTransition(c, cid)) {
                c.disconnect(true, false);
                return;
            }

            c.setPlayer(player);
            c.setAccID(player.getAccountId());

            final Hwid hwid;
            if (newcomer) {
                // 按账号拾取登录会话 hwid（原按远程 IP 取出即删，同 IP 并发登录会互相挤掉，见 doc/TODO.md）
                hwid = SessionCoordinator.getInstance().pickLoginSessionHwid(player.getAccountId());
                if (hwid == null) {
                    c.disconnect(true, false);
                    return;
                }
            } else {
                // 过渡重入：旧连接可能已死（悬挂引用）——优先从会话协调器的 hwid 缓存拾取
                // （CharSelected 时登记），缓存未命中再回退悬挂旧 Client（过渡分支不清理 hwid，仍可读）
                Hwid cached = SessionCoordinator.getInstance().getGameSessionHwid(player.getAccountId());
                hwid = cached != null ? cached : player.getClient().getHwid();
            }

            c.setHwid(hwid);

            boolean allowLogin = true;

                /*  is this check really necessary?
                if (state == Client.LOGIN_SERVER_TRANSITION || state == Client.LOGIN_NOTLOGGEDIN) {
                    List<String> charNames = c.loadCharacterNames(c.getWorld());
                    if(!newcomer) {
                        charNames.remove(player.getName());
                    }

                    for (String charName : charNames) {
                        if(wserv.getPlayerStorage().getCharacterByName(charName) != null) {
                            allowLogin = false;
                            break;
                        }
                    }
                }
                */

            int accId = c.getAccID();
            if (tryAcquireAccount(accId)) { // Sync this to prevent wrong login state for double loggedin handling
                try {
                    int state = c.getLoginState();
                    if (state != Client.LOGIN_SERVER_TRANSITION || !allowLogin) {
                        c.setPlayer(null);
                        c.setAccID(0);

                        if (state == Client.LOGIN_LOGGEDIN) {
                            c.disconnect(true, false);
                        } else {
                            c.sendPacket(PacketCreator.getAfterLoginError(7));
                        }

                        return;
                    }
                    c.updateLoginState(Client.LOGIN_LOGGEDIN);
                } finally {
                    releaseAccount(accId);
                }
            } else {
                c.setPlayer(null);
                c.setAccID(0);
                c.sendPacket(PacketCreator.getAfterLoginError(10));
                return;
            }

            if (!newcomer) {
                // 过渡重入：读悬挂旧 Client 的账号级不可变设置（language/slots，过渡分支不清理）
                c.setLanguage(player.getClient().getLanguage());
                c.setCharacterSlots((byte) player.getClient().getCharacterSlots());
            }

            // —— 会话建立（doc/12 §3.4）：attach 决定 fresh/adopt/顶号等待；入场流程（含
            // rebind）作为单个任务投递到会话 strand，FIFO 排在该会话既有任务之后——旧连接
            // 收尾与新连接进入在此获得全序。newClient 依赖会话 strand（strandSlot 安装），
            // 故一并移入入场任务。
            PlayerSession session = SessionCoordinator.getInstance().attach(c, accId);
            c.attachTo(session);
            final Character entered = player;
            final boolean firstEntry = newcomer;
            session.strand().post("loggedin-enter", () -> {
                if (!c.tryacquireClient()) {   // 与前半段的并发保护对齐（MedicOP）
                    c.sendPacket(PacketCreator.getAfterLoginError(10));
                    return;
                }
                try {
                    session.strand().rebindTo(c);   // 跨 actor 写：跑在 actor 上（doc/12 权责语义）
                    // 收包插座接线：角色把组件接插到 actor 的 Handler 槽位（on strand 写；
                    // 角色内部组成不外泄，接线知识在 Character.bindClientHandlers）
                    entered.bindClientHandlers(Player.current().clientEventHandlers());
                    enterWorld(c, entered, firstEntry);
                } finally {
                    c.releaseClient();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.releaseClient();
        }
    }

    /**
     * 入场流程：原 handlePacket 的入场段原样迁移，改在会话 strand 上执行（doc/12）。
     * 入口前置条件：rebind 已完成（Player.client == c）。
     */
    private void enterWorld(Client c, Character player, boolean newcomer) {
        final Server server = Server.getInstance();
        final World wserv = c.getWorldServer();
        final Channel cserv = c.getChannelServer();
        try {
            if (!newcomer) {
                player.newClient(c);   // 过渡重入：重绑 + 出生点重定位（newcomer 的位置由 DB 装载决定，不走此路径）
            }

            // 增加参数判断，避免给客户端发未知包导致异常
            if (GameConfig.getServerBoolean("use_server_auto_pot")) {
                byte hpAlert = hpMpAlertService.getHpAlert(player.getId());
                byte mpAlert = hpMpAlertService.getMpAlert(player.getId());
                // 仅同步给本人：该包属于客户端本地设置且不含角色标识，广播给他人可能污染其本地配置。
                // 后续如需扩展系统设置字段，可在该包尾部追加，保持前两个字节为 HP/MP 警报。
                player.sendPacket(PacketCreator.updateClientSettings(hpAlert, mpAlert));
            }
            cserv.addPlayer(player);
            wserv.addPlayer(player);
            player.setEnteredChannelWorld();

            player.resumeBuffs();   // 同对象冻结恢复（换频道/商城/MTS 重入）；未冻结时空操作

            c.sendPacket(PacketCreator.getCharInfo(player));    //这里发送登录成功封包
            if (player.isHidden()) {
                if (!GameConfig.getServerBoolean("use_auto_hide_gm")) {
                    player.toggleHide(true);
                }
            } else {
                if (player.isGM() && GameConfig.getServerBoolean("use_auto_hide_gm")) {
                    player.toggleHide(true);    //设置GM角色隐身
                }
            }
            player.sendKeymap();
            player.sendQuickmap();
            player.sendMacros();

            // pot bindings being passed through other characters on the account detected thanks to Croosade dev team
            KeyBinding autohpPot = player.getKeymap().get(91);
            player.sendPacket(PacketCreator.sendAutoHpPot(autohpPot != null ? autohpPot.getAction() : 0));

            KeyBinding autompPot = player.getKeymap().get(92);
            player.sendPacket(PacketCreator.sendAutoMpPot(autompPot != null ? autompPot.getAction() : 0));

            // 宠物召唤快照：本 strand（会话 strand）上采集后随边界传入（doc/13 §5.2）
            final List<Pet> pets = player.getPets().getSummonedPets();
            final MapleMap entryMap = player.getMap();
            final boolean firstEnter = entryMap.registerPlayer(player, pets);   // shim run：登记段缝合点
            entryMap.finishEnter(player, firstEnter, pets);                     // player strand：脚本 + self 流
            player.visitMap(player.getMap());

            BuddyList bl = player.getBuddylist();
            int[] buddyIds = bl.getBuddyIds();
            wserv.loggedOn(player.getName(), player.getId(), c.getChannel(), buddyIds);
            for (CharacterIdChannelPair onlineBuddy : wserv.multiBuddyFind(player.getId(), buddyIds)) {
                BuddylistEntry ble = bl.get(onlineBuddy.getCharacterId());
                ble.setChannel(onlineBuddy.getChannel());
                bl.put(ble);
            }
            c.sendPacket(PacketCreator.updateBuddylist(bl.getBuddies()));

            c.sendPacket(PacketCreator.loadFamily(player));
            if (player.getFamilyId() > 0) {
                Family f = wserv.getFamily(player.getFamilyId());
                if (f != null) {
                    FamilyEntry familyEntry = f.getEntryByID(player.getId());
                    if (familyEntry != null) {
                        familyEntry.setCharacter(player);
                        player.setFamilyEntry(familyEntry);

                        c.sendPacket(PacketCreator.getFamilyInfo(familyEntry));
                        familyEntry.announceToSenior(PacketCreator.sendFamilyLoginNotice(player.getName(), true), true);
                    } else {
                        log.error(I18nUtil.getLogMessage("PlayerLoggedinHandler.error.message1"), player.getName(), f.getID());
                    }
                } else {
                    log.error(I18nUtil.getLogMessage("PlayerLoggedinHandler.error.message2"), player.getName(), player.getFamilyId());
                    c.sendPacket(PacketCreator.getFamilyInfo(null));
                }
            } else {
                c.sendPacket(PacketCreator.getFamilyInfo(null));
            }

            if (player.getGuildId() > 0) {
                Guild playerGuild = server.getGuild(player.getGuildId(), player.getWorld(), player);
                if (playerGuild == null) {
                    player.deleteGuild(player.getGuildId());
                    player.getMGC().setGuildId(0);
                    player.getMGC().setGuildRank(5);
                } else {
                    playerGuild.getMGC(player.getId()).setCharacter(player);
                    player.setMGC(playerGuild.getMGC(player.getId()));
                    server.setGuildMemberOnline(player, true, c.getChannel());
                    c.sendPacket(GuildPackets.showGuildInfo(player));
                    int allianceId = player.getGuild().getAllianceId();
                    if (allianceId > 0) {
                        Alliance newAlliance = server.getAlliance(allianceId);
                        if (newAlliance == null) {
                            newAlliance = Alliance.loadAlliance(allianceId);
                            if (newAlliance != null) {
                                server.addAlliance(allianceId, newAlliance);
                            } else {
                                player.getGuild().setAllianceId(0);
                            }
                        }
                        if (newAlliance != null) {
                            c.sendPacket(GuildPackets.updateAllianceInfo(newAlliance, c.getWorld()));
                            c.sendPacket(GuildPackets.allianceNotice(newAlliance.getId(), newAlliance.getNotice()));

                            if (newcomer) {
                                server.allianceMessage(allianceId, GuildPackets.allianceMemberOnline(player, true), player.getId(), -1);
                            }
                        }
                    }
                }
            }
            //展示服务信息
            org.gms.server.quest.medal.OutstandingCitizenMedal.refreshEligibility(player);
            noteService.show(player);
            //异常地图掉线信息提示
            c.getSysRescue().showMapChangeMessage(player);

            if (player.getParty() != null) {
                PartyCharacter pchar = player.getMPC();

                //Use this in case of enabling party HPbar HUD when logging in, however "you created a party" will appear on chat.
                //c.sendPacket(PacketCreator.partyCreated(pchar));

                pchar.setChannel(c.getChannel());
                pchar.setMapId(player.getMapId());
                pchar.setOnline(true);
                wserv.updateParty(player.getParty().getId(), PartyOperation.LOG_ONOFF, pchar);
                player.updatePartyMemberHP();
            }

            InventoryTab eqpInv = player.getInventory(InventoryType.EQUIPPED);
            eqpInv.lockInventory();
            try {
                for (ItemSlot it : eqpInv.list()) {
                    it.getItem().onEquip(player, true);   // 登录装载初始化
                }
            } finally {
                eqpInv.unlockInventory();
            }

            c.sendPacket(PacketCreator.updateBuddylist(player.getBuddylist().getBuddies()));

            CharacterNameAndId pendingBuddyRequest = c.getPlayer().getBuddylist().pollPendingRequest();
            if (pendingBuddyRequest != null) {
                c.sendPacket(PacketCreator.requestBuddylistAdd(pendingBuddyRequest.getId(), c.getPlayer().getId(), pendingBuddyRequest.getName()));
            }

            c.sendPacket(PacketCreator.updateGender(player));
            player.checkMessenger();
            c.sendPacket(PacketCreator.enableReport());
            player.changeSkillLevel(10000000 * player.getJobType() + 12, (byte) (player.getLinkedLevel() / 10), 20, -1);
            player.checkBerserk(player.isHidden());

            if (newcomer) {
                // 宠物饥饿注册随 adoptPet 的召唤恢复进行（adopt 异步于登录流程，此处槽位可能未就绪）

                Mount mount = player.getMapleMount();   // thanks Ari for noticing a scenario where Silver Mane quest couldn't be started
                if (mount.getItemId() != 0) {
                    player.sendPacket(PacketCreator.updateMount(player.getId(), mount, false));
                }

                player.reloadQuestExpirations();

                    /*
                    if (!c.hasVotedAlready()){
                        player.sendPacket(PacketCreator.earnTitleMessage("You can vote now! Vote and earn a vote point!"));
                    }
                    */
                if (player.isGM()) {
                    Server.getInstance().broadcastGMMessage(c.getWorld(), PacketCreator.earnTitleMessage((player.gmLevel() < 6 ? "GM " : "Admin ") + player.getName() + " 登录了游戏"));
                } else {
                    if (GameConfig.getServerBoolean("use_login_notification")) {
                        String msg = I18nUtil.getMessage("Character.login.globalNotice", player.getName());
                        Server.getInstance().broadcastMessage(c.getWorld(), PacketCreator.serverNotice(3, c.getChannel(), msg));
                    }
                }
                // 登录展示已恢复的 debuff（applyData 已恢复，发包逻辑在 CharacterDebuffs 内部）
                player.announceDebuffsToOwner();
            } else {
                if (player.isRidingBattleship()) {
                    player.announceBattleshipHp();
                }
            }

            player.buffExpireTask();
            player.diseaseExpireTask();
            player.startSkillTimers();
            player.expirationTask();
            player.questExpirationTask();
            if (GameConstants.hasSPTable(player.getJob()) && player.getJob().getId() != 2001) {
                player.createDragon();
            }

            player.getRemote().pet().updateIgnoreList(player);
            showDueyNotification(c, player);

            player.resetPlayerRates();
            if (GameConfig.getServerBoolean("use_add_rates_by_level")) {
                player.setPlayerRates();
            }

            player.setWorldRates();

            player.receivePartyMemberHP();

            if (player.getPartnerId() > 0) {
                int partnerId = player.getPartnerId();
                final Character partner = wserv.getPlayerStorage().getCharacterById(partnerId);

                if (partner != null && !partner.isAwayFromWorld()) {
                    player.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(partnerId, partner.getMapId()));
                    partner.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(player.getId(), player.getMapId()));
                }
            }

            if (newcomer) {
                EventInstanceManager eim = EventRecallCoordinator.getInstance().recallEventInstance(player.getId());
                if (eim != null) {
                    eim.registerPlayer(player);
                }
            }

            // Tell the client to use the custom scripts available for the NPCs provided, instead of the WZ entries.
            if (GameConfig.getServerBoolean("use_npcs_scriptable")) {

                // Create a copy to prevent always adding entries to the server's list.
                Map<Integer, String> npcsIds = GameConfig.getServerObject("npcs_scriptable", new HashMap<>());

                // Any npc be specified as the rebirth npc. Allow the npc to use custom scripts explicitly.
                if (GameConfig.getServerBoolean("use_rebirth_system")) {
                    npcsIds.put(GameConfig.getServerInt("rebirth_npc_id"), "Rebirth");
                }

                c.sendPacket(PacketCreator.setNPCScriptable(npcsIds));
            }

            if (newcomer) {
                player.setLoginTime(System.currentTimeMillis());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // releaseClient 归调用方（入场任务）的 try/finally；此处不再持有 client 锁
    }

    private static void showDueyNotification(Client c, Character player) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT Type FROM dueypackages WHERE ReceiverId = ? AND Checked = 1 ORDER BY Type DESC")) {
            ps.setInt(1, player.getId());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement ps2 = con.prepareStatement("UPDATE dueypackages SET Checked = 0 WHERE ReceiverId = ?")) {
                        ps2.setInt(1, player.getId());
                        ps2.executeUpdate();

                        c.sendPacket(PacketCreator.sendDueyParcelNotification(rs.getInt("Type") == 1));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

}
