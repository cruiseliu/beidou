/* 
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any otheer version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; witout even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gms.client.character;

import lombok.Getter;
import lombok.Setter;

import org.gms.client.BuddyList;
import org.gms.client.BuddylistEntry;
import org.gms.client.EffectType;
import org.gms.client.Client;
import org.gms.client.Disease;
import org.gms.client.Family;
import org.gms.client.FamilyEntry;
import org.gms.client.JobEnum;
import org.gms.client.MonsterBook;
import org.gms.client.Mount;
import org.gms.client.QuestStatus;
import org.gms.client.Ring;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.SkillMacro;
import org.gms.client.SkinColor;
import org.gms.client.PacketStat;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.creator.CharacterFactoryRecipe;
import org.gms.client.inventory.*;
import org.gms.client.inventory.Equip.StatUpgrade;
import org.gms.client.keybind.KeyBinding;
import org.gms.client.keybind.QuickslotBinding;
import org.gms.config.GameConfig;
import org.gms.constants.game.DelayedQuestUpdate;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MapId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.net.ServerConstants;
import org.gms.constants.skills.*;
import org.gms.constants.string.ExtendKey;
import org.gms.dao.entity.*;
import org.gms.manager.ServerManager;
import org.gms.model.dto.InventorySearchReqDTO;
import org.gms.model.dto.InventorySearchRtnDTO;
import org.gms.model.json.CharacterData;
import org.gms.model.pojo.NewYearCardRecord;
import org.gms.model.pojo.SkillEntry;
import org.gms.net.packet.Packet;
import org.gms.net.server.PlayerCoolDownValueHolder;
import org.gms.net.server.Server;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.net.server.guild.Alliance;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.guild.GuildCharacter;
import org.gms.net.server.services.task.world.CharacterSaveService;
import org.gms.net.server.services.type.WorldServices;
import org.gms.net.server.world.*;
import org.gms.scripting.AbstractPlayerInteraction;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.*;
import org.gms.server.events.Events;
import org.gms.server.events.RescueGaga;
import org.gms.server.events.gm.Fitness;
import org.gms.server.events.gm.Ola;
import org.gms.server.life.*;
import org.gms.server.maps.*;
import org.gms.server.maps.MiniGame.MiniGameResult;
import org.gms.server.minigame.RockPaperScissor;
import org.gms.server.partyquest.AriantColiseum;
import org.gms.server.partyquest.MonsterCarnival;
import org.gms.server.partyquest.MonsterCarnivalParty;
import org.gms.server.partyquest.PartyQuest;
import org.gms.server.quest.Quest;
import org.gms.service.*;
import org.gms.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.sql.*;
import java.util.List;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import static org.gms.client.character.Stat.*;

import static java.util.concurrent.TimeUnit.*;

public class Character extends AbstractAnimatedMapObject {
    private static final Logger log = LoggerFactory.getLogger(Character.class);

    // ── 属性核心（原 AbstractCharacterObject 合并而来） ──
    final CharacterStats stats = new CharacterStats(this);
    final CharacterAp ap = new CharacterAp(this);
    final CharacterSp sp = new CharacterSp(this);
    // ActiveBuffs 实例由 CharacterBuffs 内部组合创建（见 CharacterBuffs 构造器）
    final CharacterBuffs buffs = new CharacterBuffs(this);
    final CharacterPets pets = new CharacterPets(this);
    final CharacterDebuffs debuffs = new CharacterDebuffs(this);
    final CharacterChair chair = new CharacterChair(this);
    final CharacterJob job = new CharacterJob(this);
    final CharacterMap map = new CharacterMap(this);
    final CharacterRates rates = new CharacterRates(this);
    final CharacterAntiCheat antiCheat = new CharacterAntiCheat(this);
    final CharacterMarket market = new CharacterMarket(this);
    final CharacterQuests quests = new CharacterQuests(this);
    final CharacterParty party = new CharacterParty(this);
    final CharacterMysticDoor door = new CharacterMysticDoor(this);
    final CharacterPartyQuest pq = new CharacterPartyQuest(this);
    final CharacterGuild guild = new CharacterGuild(this);
    final CharacterInventory inventory = new CharacterInventory(this);
    final CharacterFamily family = new CharacterFamily(this);
    final CharacterMarriage marriage = new CharacterMarriage(this);
    final CharacterMiniGame miniGame = new CharacterMiniGame(this);
    final CharacterLevel level = new CharacterLevel(this);
    final CharacterFame fame = new CharacterFame(this);
    final CharacterGm gm = new CharacterGm(this);
    final CharacterRebirth reborn = new CharacterRebirth(this);
    final CharacterDeath death = new CharacterDeath(this);
    final CharacterKeyBinding keybinding = new CharacterKeyBinding(this);
    final CharacterStorage storage = new CharacterStorage();
    final CharacterSpecialSkills specialSkills = new CharacterSpecialSkills(this);
    final CharacterSalon salon = new CharacterSalon(this);
    final CharacterBuddies buddy = new CharacterBuddies(this);

    @Getter
    @Setter
    private int world;
    @Getter
    @Setter
    int id;
    @Getter
    @Setter
    private int accountId;
    @Getter
    @Setter
    private int rank;
    @Getter
    @Setter
    private int rankMove;
    @Getter
    @Setter
    private int jobRank;
    @Getter
    @Setter
    private int jobRankMove;
    @Setter
    @Getter
    private int gender;
    @Getter
    @Setter
    private int initialSpawnPoint;
    @Getter
    private int currentPage;
    @Getter
    private int currentType = 0;
    @Getter
    private int currentTab = 1;
    @Setter
    @Getter
    private int itemEffect;
    @Setter
    @Getter
    private int messengerPosition = 4;

    @Getter
    private int ci = 0;
    @Setter
    private int bookCover;
    @Setter
    @Getter
    private int mesosTraded = 0;
    @Getter
    private int possibleReports = 10;
    @Setter
    @Getter
    private int dojoPoints;
    @Getter
    @Setter
    private int vanquisherStage;
    @Setter
    @Getter
    private int dojoStage;
    @Getter
    private int dojoEnergy;
    @Getter
    @Setter
    private int vanquisherKills;
    @Getter
    @Setter
    private int owlSearch;
    @Setter
    @Getter
    private long lastUsedCashItem;
    private boolean berserk;

    @Setter
    @Getter
    private int linkedLevel = 0;
    @Getter
    @Setter
    private String linkedName = null;
    @Getter
    @Setter
    private boolean finishedDojoTutorial;
    @Getter
    @Setter
    private String name;
    private String chalktext;
    private String commandtext;
    @Getter
    @Setter
    private String search = null;
    final AtomicBoolean awayFromWorld = new AtomicBoolean(true);  // player is online, but on cash shop or mts
    private final AtomicInteger meso = new AtomicInteger();
    private EventInstanceManager eventInstance = null;
    @Getter
    @Setter
    Client client;
    @Getter
    @Setter
    private Messenger messenger = null;
    @Getter
    @Setter
    private Mount mapleMount;
    @Getter
    @Setter
    private Shop shop = null;
    @Getter
    @Setter
    private Trade trade = null;
    @Getter
    @Setter
    private MonsterBook monsterBook;
    @Getter
    @Setter
    private CashShop cashShop;
    private final Set<NewYearCardRecord> newyears = new LinkedHashSet<>();
    @Getter
    private final SavedLocation[] savedLocations;
    @Getter
    private final SkillMacro[] skillMacros = new SkillMacro[5];
    private WeakReference<MapleMap> ownedMap = new WeakReference<>(null);
    private final Set<Monster> controlled = new LinkedHashSet<>();
    private final Map<Integer, String> entered = new LinkedHashMap<>();
    private final Set<MapObject> visibleMapObjects = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final CharacterSkills skills = new CharacterSkills(this);

    final Map<Integer, Summon> summons = new LinkedHashMap<>();
    ScheduledFuture<?> dragonBloodSchedule;
    private ScheduledFuture<?> hpDecreaseTask;
    ScheduledFuture<?> beholderHealingSchedule, beholderBuffSchedule, berserkSchedule;
    ScheduledFuture<?> recoveryTask = null;
    ScheduledFuture<?> extraRecoveryTask = null;
    private ScheduledFuture<?> cpqSchedule = null;

    final Lock chrLock = new ReentrantLock(true);
    private final Lock evtLock = new ReentrantLock(true);
    private final Lock cpnLock = new ReentrantLock();
    @Getter
    private final Set<Integer> disabledPartySearchInvites = new LinkedHashSet<>();
    private long portaldelay = 0;
    @Getter
    @Setter
    private long lastCombo = 0;
    private short combocounter = 0;
    @Getter
    private final List<String> blockedPortals = new ArrayList<>();
    private final Map<Short, String> area_info = new LinkedHashMap<>();
    private boolean blockCashShop = false;
    boolean allowExpGain = true;    // 包内可见：CharacterInventory.increaseEquipExp 读取
    byte pendantExp = 0;    // 包内可见：CharacterInventory 精灵吊坠逻辑读写
    private final List<Integer> trockmaps = new ArrayList<>();
    private final List<Integer> viptrockmaps = new ArrayList<>();
    @Getter
    private Map<String, Events> events = new LinkedHashMap<>();
    @Setter
    @Getter
    private Dragon dragon = null;
    @Getter
    @Setter
    private boolean loggedIn = false;
    @Getter
    private long npcCd;
    byte extraHpRec = 0, extraMpRec = 0;
    short extraRecInterval;
    @Setter
    @Getter
    private int targetHpBarHash = 0;
    @Setter
    @Getter
    private long targetHpBarTime = 0;
    private long nextWarningTime = 0;
    long lastExpGainTime;    // 包内可见：CharacterLevel.gainExpInternal 写入
    private boolean pendingNameChange; //only used to change name on logout, not to be relied upon elsewhere
    @Getter
    @Setter
    private long loginTime;
    @Setter
    @Getter
    private boolean chasing = false;

    static final CharacterService characterService = ServerManager.getApplicationContext().getBean(CharacterService.class);
    private static final NameChangeService nameChangeService = ServerManager.getApplicationContext().getBean(NameChangeService.class);
    private static final WorldTransferService worldTransferService = ServerManager.getApplicationContext().getBean(WorldTransferService.class);
    static final AccountService accountService = ServerManager.getApplicationContext().getBean(AccountService.class);    // 包内可见：CharacterAntiCheat.ban/block 调用
    static final HpMpAlertService hpMpAlertService = ServerManager.getApplicationContext().getBean(HpMpAlertService.class);    // 包内可见：CharacterStats.applyHpMpChange 调用
    private static final InventoryService inventoryService = ServerManager.getApplicationContext().getBean(InventoryService.class);

    public int getClientMaxHp() {
        return stats.getClientMaxHp();
    }

    public int getClientMaxMp() {
        return stats.getClientMaxMp();
    }

    private Character() {
        setStance(0);
        savedLocations = new SavedLocation[SavedLocationType.values().length];

        for (int i = 0; i < SavedLocationType.values().length; i++) {
            savedLocations[i] = null;
        }
        setPosition(new Point(0, 0));
    }

    public boolean isAlive() {
        return stats.getHp() > 0;
    }

    public int getHp() {
        return stats.getHp();
    }

    public int getMp() {
        return stats.getMp();
    }

    public int getMaxHp() {
        return stats.getBase(Stat.MAX_HP);
    }

    public int getMaxMp() {
        return stats.getBase(Stat.MAX_MP);
    }

    public int getCurrentMaxHp() {
        return stats.getTotal(Stat.MAX_HP);
    }

    public int getCurrentMaxMp() {
        return stats.getTotal(Stat.MAX_MP);
    }

    public boolean assignStrDexIntLuk(int deltaStr, int deltaDex, int deltaInt, int deltaLuk) {
        Integer[] delta = new Integer[Stat.count()];
        delta[STR.ordinal()] = deltaStr;
        delta[DEX.ordinal()] = deltaDex;
        delta[INT.ordinal()] = deltaInt;
        delta[LUK.ordinal()] = deltaLuk;
        return ap.assignAttrs(delta);
    }

    /** 四维全部设为 x（管理命令用） */
    public void updateStrDexIntLuk(int x) {
        stats.update().set(STR, x).set(DEX, x).set(INT, x).set(LUK, x).commit();
    }

    private void setRemainingSp(int[] sps) {
        sp.setRemainingSp(sps);
    }

    private void updateRemainingSp(int remainingSp, int jobId) {
        sp.changeRemainingSp(remainingSp, jobId, false);
    }

    public static Character getDefault(Client c) {
        Character ret = new Character();
        ret.client = c;
        ret.gm.setGMLevel(0);
        ret.stats.update()
                .set(MAX_HP, 50)
                .set(MAX_MP, 5)
                .setHp(50)
                .setMp(5)
                .set(STR, 12)
                .set(DEX, 5)
                .set(INT, 4)
                .set(LUK, 4)
                .commitSilently();
        ret.setMap((MapleMap) null);
        ret.setJob(JobEnum.BEGINNER);
        ret.level.setLevel(1);
        ret.accountId = c.getAccID();

        ret.mapleMount = null;
        ret.getInventory(InventoryType.EQUIP).setSlotLimit(24);
        ret.getInventory(InventoryType.USE).setSlotLimit(24);
        ret.getInventory(InventoryType.SETUP).setSlotLimit(24);
        ret.getInventory(InventoryType.ETC).setSlotLimit(24);

        //to fix the map 0 lol
        for (int i = 0; i < 5; i++) {
            ret.trockmaps.add(MapId.NONE);
        }
        for (int i = 0; i < 10; i++) {
            ret.viptrockmaps.add(MapId.NONE);
        }

        return ret;
    }

    public boolean isLoggedInWorld() {
        return this.isLoggedIn() && !this.isAwayFromWorld();
    }

    public boolean isAwayFromWorld() {
        return awayFromWorld.get();
    }

    public void setEnteredChannelWorld() {
        awayFromWorld.set(false);
        client.getChannelServer().removePlayerAway(id);

        if (party.canRecvPartySearchInvite) {
            this.getWorldServer().getPartySearchCoordinator().attachPlayer(this);
        }
    }

    public void setAwayFromChannelWorld() {
        setAwayFromChannelWorld(false);
    }

    public void setDisconnectedFromChannelWorld() {
        setAwayFromChannelWorld(true);
    }

    private void setAwayFromChannelWorld(boolean disconnect) {
        awayFromWorld.set(true);

        if (!disconnect) {
            client.getChannelServer().insertPlayerAway(id);
        } else {
            client.getChannelServer().removePlayerAway(id);
        }
    }

    public void setSessionTransitionState() {
        client.setCharacterOnSessionTransitionState(this.getId());
    }

    public long getNpcCooldown() {
        return npcCd;
    }

    public void setNpcCooldown(long d) {
        npcCd = d;
    }

    public int addDojoPointsByMap(int mapId) {
        int pts = 0;
        if (dojoPoints < 17000) {
            pts = 1 + ((mapId - 1) / 100 % 100) / 6;
            if (!MapId.isPartyDojo(this.getMapId())) {
                pts++;
            }
            this.dojoPoints += pts;
        }
        return pts;
    }

    public void addMesosTraded(int gain) {
        this.mesosTraded += gain;
    }

    /**
     * 召唤物/傀儡的地图侧移除：广播、地图对象、登记与小灵伴随调度清理。
     * 语义上属召唤物域（地图对象管理），非 buff——由 buff 取消链调用。
     */
    void removeSummonAndPuppet(Summon summon) {
        getMap().broadcastMessage(PacketCreator.removeSummon(summon, true), summon.getPosition());
        getMap().removeMapObject(summon);
        removeVisibleMapObject(summon);

        summons.remove(summon.getSkill());
        if (summon.isPuppet()) {
            getMap().removePlayerPuppet(this);
        } else if (summon.getSkill() == DarkKnight.BEHOLDER) {
            if (beholderHealingSchedule != null) {
                beholderHealingSchedule.cancel(false);
                beholderHealingSchedule = null;
            }
            if (beholderBuffSchedule != null) {
                beholderBuffSchedule.cancel(false);
                beholderBuffSchedule = null;
            }
        }
    }

    public void addSummon(int id, Summon summon) {
        summons.put(id, summon);

        if (summon.isPuppet()) {
            getMap().addPlayerPuppet(this);
        }
    }

    public void addVisibleMapObject(MapObject mo) {
        visibleMapObjects.add(mo);
    }

    public int calculateMaxBaseDamage(int watk, WeaponType weapon) {
        int mainstat, secondarystat;
        if (job.isA(JobEnum.THIEF) && weapon == WeaponType.DAGGER_OTHER) {
            weapon = WeaponType.DAGGER_THIEVES;
        }

        if (weapon == WeaponType.BOW || weapon == WeaponType.CROSSBOW || weapon == WeaponType.GUN) {
            mainstat = stats.getTotal(DEX);
            secondarystat = stats.getTotal(STR);
        } else if (weapon == WeaponType.CLAW || weapon == WeaponType.DAGGER_THIEVES) {
            mainstat = stats.getTotal(LUK);
            secondarystat = stats.getTotal(DEX) + stats.getTotal(STR);
        } else {
            mainstat = stats.getTotal(STR);
            secondarystat = stats.getTotal(DEX);
        }
        return (int) Math.ceil(((weapon.getMaxDamageMultiplier() * mainstat + secondarystat) / 100.0) * watk);
    }

    public int calculateMaxBaseDamage(int watk) {
        int maxbasedamage;
        Item weapon_item = getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        if (weapon_item != null) {
            maxbasedamage = calculateMaxBaseDamage(watk, ItemInformationProvider.getInstance().getWeaponType(weapon_item.getItemId()));
        } else {
            if (job.isA(JobEnum.PIRATE) || job.isA(JobEnum.THUNDERBREAKER1)) {
                double weapMulti = 3;
                if (job.getId() % 100 != 0) {
                    weapMulti = 4.2;
                }

                int attack = (int) Math.min(Math.floor((2D * getLevel() + 31) / 3), 31);
                maxbasedamage = (int) Math.ceil((stats.getTotal(STR) * weapMulti + stats.getTotal(DEX)) * attack / 100.0);
            } else {
                maxbasedamage = 1;
            }
        }
        return maxbasedamage;
    }

    public int calculateMaxBaseMagicDamage(int matk) {
        int maxbasedamage = matk;
        int totalint = getTotalInt();

        if (totalint > 2000) {
            maxbasedamage -= 2000;
            maxbasedamage += (int) ((0.09033024267 * totalint) + 3823.8038);
        } else {
            maxbasedamage -= totalint;

            if (totalint > 1700) {
                maxbasedamage += (int) (0.1996049769 * Math.pow(totalint, 1.300631341));
            } else {
                maxbasedamage += (int) (0.1996049769 * Math.pow(totalint, 1.290631341));
            }
        }

        return (maxbasedamage * 107) / 100;
    }

    public void setCombo(short count) {
        if (count < combocounter) {
            cancelEffectFromBuffStat(EffectType.ARAN_COMBO);
        }
        combocounter = (short) Math.min(30000, count);
        if (count > 0) {
            sendPacket(PacketCreator.showCombo(combocounter));
        }
    }

    public short getCombo() {
        return combocounter;
    }

    public boolean cannotEnterCashShop() {
        return blockCashShop;
    }

    public void toggleBlockCashShop() {
        blockCashShop = !blockCashShop;
    }

    public void toggleExpGain() {
        allowExpGain = !allowExpGain;
    }

    public void newClient(Client c) {
        this.loggedIn = true;
        c.setAccountName(this.client.getAccountName());//No null's for accountName
        this.setClient(c);
        setMap(c.getChannelServer().getMapFactory().getMap(getMapId()));
        Portal portal = getMap().findClosestPlayerSpawnpoint(getPosition());
        if (portal == null) {
            portal = getMap().getPortal(0);
        }
        this.setPosition(portal.getPosition());
        this.initialSpawnPoint = portal.getId();
    }

    public String getMedalText() {
        String medal = "";
        final Item medalItem = getInventory(InventoryType.EQUIPPED).getItem((short) -49);
        if (medalItem != null) {
            medal = "<" + ItemInformationProvider.getInstance().getName(medalItem.getItemId()) + "> ";
        }
        return medal;
    }

    public static boolean canCreateChar(String name) {
        String lname = name.toLowerCase();
        for (String nameTest : ServerConstants.BLOCKED_NAMES) {
            if (lname.contains(nameTest)) {
                return false;
            }
        }
        return !existName(name) && Pattern.compile("[a-zA-Z0-9\u4e00-\u9fa5]{2,12}").matcher(name).matches(); // 加入对中文编码的检测
    }

    public static boolean existName(String name) {
        try {
            if (characterService.findByName(name) != null) {
                return true;
            }
            if (!nameChangeService.getAllNameChanges().isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Character.ban.error2"), e);
        }
        return false;
    }

    public void changeCI(int type) {
        this.ci = type;
    }

    public void broadcastAcquaintances(int type, String message) {
        broadcastAcquaintances(PacketCreator.serverNotice(type, message));
    }

    public void broadcastAcquaintances(Packet packet) {
        buddy.getBuddylist().broadcast(packet, getWorldServer().getPlayerStorage());
        Family family = getFamily();
        if (family != null) {
            family.broadcast(packet, id);
        }

        Guild guild = getGuild();
        if (guild != null) {
            guild.broadcast(packet, id);
        }

        /*
        if(partnerid > 0) {
            partner.sendPacket(packet); not yet implemented
        }
        */
        sendPacket(packet);
    }

    public void broadcastStance(int newStance) {
        setStance(newStance);
        broadcastStance();
    }

    public void broadcastStance() {
        getMap().broadcastMessage(this, PacketCreator.movePlayer(id, this.getIdleMovement(), AbstractAnimatedMapObject.IDLE_MOVEMENT_PACKET_LENGTH), false);
    }

    private boolean buffMapProtection() {
        return map.buffMapProtection();
    }

    public void setOwnedMap(MapleMap map) {
        ownedMap = new WeakReference<>(map);
    }

    public MapleMap getOwnedMap() {
        return ownedMap.get();
    }

    public void removeIncomingInvites() {
        InviteCoordinator.removePlayerIncomingInvites(id);
    }

    public void changePage(int page) {
        this.currentPage = page;
    }

    public void changeTab(int tab) {
        this.currentTab = tab;
    }

    public void changeType(int type) {
        this.currentType = type;
    }

    public void checkBerserk(final boolean isHidden) {
        if (berserkSchedule != null) {
            berserkSchedule.cancel(false);
        }
        final Character chr = this;
        if (job.equalsJob(JobEnum.DARKKNIGHT)) {
            Skill BerserkX = SkillFactory.getSkill(DarkKnight.BERSERK);
            final int skilllevel = getSkillLevel(BerserkX);
            if (skilllevel > 0) {
                berserk = chr.getHp() * 100 / chr.getCurrentMaxHp() < BerserkX.getEffect(skilllevel).getX();
                berserkSchedule = TimerManager.getInstance().register(() -> {
                    if (awayFromWorld.get()) {
                        return;
                    }

                    sendPacket(PacketCreator.showOwnBerserk(skilllevel, berserk));
                    if (!isHidden) {
                        getMap().broadcastMessage(Character.this, PacketCreator.showBerserk(getId(), skilllevel, berserk), false);
                    } else {
                        getMap().broadcastGMMessage(Character.this, PacketCreator.showBerserk(getId(), skilllevel, berserk), false);
                    }
                }, 5000, 3000);
            }
        }
    }

    public void checkMessenger() {
        if (messenger != null && messengerPosition < 4 && messengerPosition > -1) {
            World worldz = getWorldServer();
            worldz.silentJoinMessenger(messenger.getId(), new MessengerCharacter(this, messengerPosition), messengerPosition);
            worldz.updateMessenger(getMessenger().getId(), name, client.getChannel());
        }
    }

    public void controlMonster(Monster monster) {
        if (cpnLock.tryLock()) {
            try {
                controlled.add(monster);
            } finally {
                cpnLock.unlock();
            }
        }
    }

    public void stopControllingMonster(Monster monster) {
        if (cpnLock.tryLock()) {
            try {
                controlled.remove(monster);
            } finally {
                cpnLock.unlock();
            }
        }
    }

    public int getNumControlledMonsters() {
        cpnLock.lock();
        try {
            return controlled.size();
        } finally {
            cpnLock.unlock();
        }
    }

    public Collection<Monster> getControlledMonsters() {
        cpnLock.lock();
        try {
            return new ArrayList<>(controlled);
        } finally {
            cpnLock.unlock();
        }
    }

    public void releaseControlledMonsters() {
        Collection<Monster> controlledMonsters;

        cpnLock.lock();
        try {
            controlledMonsters = new ArrayList<>(controlled);
            controlled.clear();
        } finally {
            cpnLock.unlock();
        }

        for (Monster monster : controlledMonsters) {
            monster.aggroRedirectController();
        }
    }

    /**
     * 处理拾取后立即消耗的道具逻辑
     *
     * @param itemId 物品的唯一标识ID，应符合游戏物品ID规范（消耗品类ID以2开头）
     * @return boolean 消耗是否成功应用：
     *                 - true: 道具效果已应用/处理完成
     *                 - false: 非消耗品或无需立即使用
     * @throws NullPointerException 如果无法获取物品信息或效果对象可能抛出
     *
     * @description
     * 实现以下核心逻辑：
     * 1. 验证物品是否为可消耗类型（ID首数字为2）
     * 2. 检查物品的"拾取即用"标记
     * 3. 处理队伍道具的特殊场景：
     *    - 普通队伍道具：对同地图存活队友应用效果
     *    - 全体治疗道具：解除队友异常状态
     * 4. 处理怪物卡片收集（ID 238xxxx类型）
     *
     * @example
     * // 典型使用场景
     * if(applyConsumeOnPickup(2001000)) {
     *     removeFromInventory(item); // 消耗后移除物品
     * }
     *
     * @note
     * - 物品ID格式约定：
     *   - 第1位：物品大类（2=消耗品）
     *   - 第2-4位：物品子类（238=怪物卡片）
     * - 队伍道具效果只会影响同地图的存活队友
     */
    public boolean applyConsumeOnPickup(final int itemId) {// 判断拾取后是否立即消耗道具的方法
        if (itemId / 1000000 != 2) {// 检查物品ID是否属于消耗品类（假设ID以2开头）
            return false; // 非消耗品直接返回不处理
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();// 获取物品信息提供器实例
        if (!ii.isConsumeOnPickup(itemId)) {// 检查该物品是否标记为"拾取后立即使用"
            return false; // 无需立即使用则返回
        }
        if (ItemConstants.isPartyItem(itemId)) {// 判断是否为队伍共享类道具
            List<Character> partyMembers = this.getPartyMembersOnSameMap();// 获取同一地图内的队伍成员列表
            if (!ItemId.isPartyAllCure(itemId)) {// 处理非全体治疗类道具
                BuffEffectData mse = ii.getItemEffect(itemId);// 获取道具效果对象
                if (!partyMembers.isEmpty()) {
                    for (Character mc : partyMembers) {// 遍历存活队友并施加效果
                        if (mc.isAlive()) {
                            mse.applyTo(mc);
                        }
                    }
                } else if (this.isAlive()) {
                    mse.applyTo(this);// 无队友时对自己生效
                }
            } else {
                if (!partyMembers.isEmpty()) {// 处理全体治疗类道具（如解除异常状态）
                    for (Character mc : partyMembers) {
                        mc.dispelDebuffs(); // 解除队友debuff
                    }
                } else {
                    this.dispelDebuffs(); // 无队友时解除自身
                }
            }
        } else {
            ii.getItemEffect(itemId).applyTo(this);// 非队伍道具直接对自身生效
        }

        if (itemId / 10000 == 238) {// 特殊处理怪物卡片收集（ID格式238xxxx）
            this.getMonsterBook().addCard(client, itemId); // 添加到怪物图鉴
        }
        return true; // 成功执行消耗操作
    }

    public void decreaseReports() {
        this.possibleReports--;
    }

    public static boolean deleteCharFromDB(Character player, int senderAccId) {
        try {
            characterService.deleteCharFromDB(player, senderAccId);
            // NOTE: 删除缓存,防止角色槽满后无法再次建立角色
            Server.getInstance().deleteCharacterEntry(senderAccId, player.getId());
            return true;
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Character.deleteCharFromDB.error1"), e);
        }
        return false;
    }

    private void deleteWhereCharacterId(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    void stopChairTask() {
        chair.stopChairTask();
    }

    void startChairTask() {
        chair.startChairTask();
    }

    void stopExtraTask() {
        chrLock.lock();
        try {
            if (extraRecoveryTask != null) {
                extraRecoveryTask.cancel(false);
                extraRecoveryTask = null;
            }
        } finally {
            chrLock.unlock();
        }
    }

    void startExtraTask(final byte healHP, final byte healMP, final short healInterval) {
        chrLock.lock();
        try {
            startExtraTaskInternal(healHP, healMP, healInterval);
        } finally {
            chrLock.unlock();
        }
    }

    void startExtraTaskInternal(final byte healHP, final byte healMP, final short healInterval) {
        extraRecInterval = healInterval;

        extraRecoveryTask = TimerManager.getInstance().register(() -> {
            if (getBuffSource(EffectType.HPREC) == -1 && getBuffSource(EffectType.MPREC) == -1) {
                stopExtraTask();
                return;
            }

            if (Character.this.getHp() < stats.getTotal(Stat.MAX_HP)) {
                if (healHP > 0) {
                    sendPacket(PacketCreator.showOwnRecovery(healHP));
                    getMap().broadcastMessage(Character.this, PacketCreator.showRecovery(id, healHP), false);
                }
            }

            addMPHP(healHP, healMP);
        }, healInterval, healInterval);
    }

    public void doHurtHp() {
        if (!(this.getInventory(InventoryType.EQUIPPED).findById(getMap().getHPDecProtect()) != null || buffMapProtection())) {
            addHP(-getMap().getHPDec());
            sendPacket(PacketCreator.onNotifyHPDecByField(getMap().getHPDec()));
        }
    }

    public void dropMessage(String message) {
        dropMessage(0, message);
    }

    /**
     * 给玩家角色发送消息
     * @param type  0=聊天窗[note]蓝色消息；1=中间弹窗；2=？；3=？；4=？；5=聊天窗红色消息；6=聊天窗黄色消息
     * @param message
     */
    public void dropMessage(int type, String message) {
        sendPacket(PacketCreator.serverNotice(type, message));
    }

    public void enteredScript(String script, int mapid) {
        if (!entered.containsKey(mapid)) {
            entered.put(mapid, script);
        }
    }

    public void gainMeso(int gain) {
        gainMeso(gain, true, false, true);
    }

    public void gainMeso(int gain, boolean show) {
        gainMeso(gain, show, false, false);
    }

    public void gainMeso(int gain, boolean show, boolean enableActions, boolean inChat) {
        long nextMeso;
        // meso 为 AtomicInteger，原 mapHistoryLock 保护系误用（与 map 历史无关）；改用 CAS 循环保证读改写原子
        int cur;
        do {
            cur = meso.get();
            nextMeso = (long) cur + gain;  // thanks Thora for pointing integer overflow here
            if (nextMeso > Integer.MAX_VALUE) {
                nextMeso = Integer.MAX_VALUE;
            } else if (nextMeso < 0) {
                nextMeso = 0;
            }
        } while (!meso.compareAndSet(cur, (int) nextMeso));
        gain = (int) (nextMeso - cur);

        if (gain != 0) {
            updateSingleStat(PacketStat.MESO, (int) nextMeso, enableActions);
            if (show) {
                sendPacket(PacketCreator.getShowMesoGain(gain, inChat));
            }
        } else {
            enableActions();
        }
    }

    public void cancelEffect(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        buffs.cancelBuff(ii.getItemEffect(itemId).getBuffSourceId(), false);
    }

    public void freezeBuffs(boolean announceCancel) {
        buffs.freeze(announceCancel);
        debuffs.freeze();
    }

    public void resumeBuffs() {
        buffs.resume();
        debuffs.resume();
    }

    /**
     * 离开游戏世界（换频道/商城/MTS）时的召唤物地图侧清理：广播移除、摘除地图对象与 puppet 关联。
     * summons 登记与小灵伴随调度保留在对象上（冻结期间靠 awayFromWorld 守卫空转）。
     */
    public void removeSummonsFromMap() {
        for (Summon summon : new ArrayList<>(summons.values())) {
            getMap().broadcastMessage(PacketCreator.removeSummon(summon, true), summon.getPosition());
            getMap().removeMapObject(summon);
            removeVisibleMapObject(summon);
            if (summon.isPuppet()) {
                getMap().removePlayerPuppet(this);
            }
        }
    }

    public String getChalkboard() {
        return this.chalktext;
    }

    public AbstractPlayerInteraction getAbstractPlayerInteraction() {
        return client.getAbstractPlayerInteraction();
    }

    public EventInstanceManager getEventInstance() {
        evtLock.lock();
        try {
            return eventInstance;
        } finally {
            evtLock.unlock();
        }
    }

    public boolean isMale() {
        return getGender() == 0;
    }

    public static int getAccountIdByName(String name) {
        final int id;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT accountid FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                id = rs.getInt("accountid");
            }
            return id;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    public static int getIdByName(String name) {
        final int id;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                id = rs.getInt("id");
            }
            return id;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    public static String getNameById(int id) {
        final String name;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT name FROM characters WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                name = rs.getString("name");
            }
            return name;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public int getFh() {
        Point pos = this.getPosition();
        pos.y -= 6;

        if (getMap().getFootholds().findBelow(pos) == null) {
            return 0;
        } else {
            return getMap().getFootholds().findBelow(pos).getY1();
        }
    }

    public int getTotalStr() {
        return stats.getTotal(STR);
    }

    public int getTotalDex() {
        return stats.getTotal(DEX);
    }

    public int getTotalInt() {
        return stats.getTotal(INT);
    }

    public int getTotalLuk() {
        return stats.getTotal(LUK);
    }

    public int getTotalMagic() {
        return stats.getMagicPower();
    }

    public int getTotalWatk() {
        return stats.getTotal(Stat.P_ATK);
    }

    public int getMaxClassLevel() {
        return job.getMaxClassLevel();
    }

    public int getMaxLevel() {
        return job.getMaxLevel();
    }

    public int getMeso() {
        return meso.get();
    }

    public void setMeso(int meso) {
        this.meso.set(meso);
    }

    public void setPlayerAggro(int mobHash) {
        setTargetHpBarHash(mobHash);
        setTargetHpBarTime(System.currentTimeMillis());
    }

    public void resetPlayerAggro() {
        if (getWorldServer().unregisterDisabledServerMessage(id)) {
            client.announceServerMessage();
        }

        setTargetHpBarHash(0);
        setTargetHpBarTime(0);
    }

    public int getMonsterBookCover() {
        return bookCover;
    }

    public void closePartySearchInteractions() {
        this.getWorldServer().getPartySearchCoordinator().unregisterPartyLeader(this);
        if (party.canRecvPartySearchInvite) {
            this.getWorldServer().getPartySearchCoordinator().detachPlayer(this);
        }
    }

    public void closePlayerInteractions() {
        closeNpcShop();
        closeTrade();
        market.closePlayerShop();
        miniGame.closeMiniGame(true);
        miniGame.closeRPS();
        market.closeHiredMerchant(false);
        closePlayerMessenger();

        client.closePlayerScriptInteractions();
        resetPlayerAggro();
    }

    public void closeNpcShop() {
        setShop(null);
    }

    public void closeTrade() {
        Trade.cancelTrade(this, Trade.TradeResult.PARTNER_CANCEL);
    }

    public void closePlayerMessenger() {
        Messenger m = this.getMessenger();
        if (m == null) {
            return;
        }

        World w = getWorldServer();
        w.leaveMessenger(m.getId(), new MessengerCharacter(this, this.getMessengerPosition()));
        this.setMessenger(null);
        this.setMessengerPosition(4);
    }

    public void clearSavedLocation(SavedLocationType type) {
        savedLocations[type.ordinal()] = null;
    }

    public int peekSavedLocation(String type) {
        SavedLocation sl = savedLocations[SavedLocationType.fromString(type).ordinal()];
        if (sl == null) {
            return -1;
        }
        return sl.getMapId();
    }

    public int getSavedLocation(String type) {
        int m = peekSavedLocation(type);
        clearSavedLocation(SavedLocationType.fromString(type));

        return m;
    }

    public Map<Skill, SkillEntry> getEditableSkills() {
        return skills.entries;
    }

    public Collection<Summon> getSummonsValues() {
        return summons.values();
    }

    public void clearSummons() {
        summons.clear();
    }

    public Summon getSummonByKey(int id) {
        return summons.get(id);
    }

    public boolean isSummonsEmpty() {
        return summons.isEmpty();
    }

    public boolean containsSummon(Summon summon) {
        return summons.containsValue(summon);
    }

    public MapObject[] getVisibleMapObjects() {
        return visibleMapObjects.toArray(new MapObject[visibleMapObjects.size()]);
    }

    public World getWorldServer() {
        return Server.getInstance().getWorld(world);
    }

    public boolean hasEntered(String script) {
        for (int mapId : entered.keySet()) {
            if (entered.get(mapId).equals(script)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasEntered(String script, int mapId) {
        String e = entered.get(mapId);
        return script.equals(e);
    }

    public boolean isMapObjectVisible(MapObject mo) {
        return visibleMapObjects.contains(mo);
    }

    public boolean attemptCatchFish(int baitLevel) {
        return GameConfig.getServerBoolean("use_fishing_system") && MapId.isFishingArea(getMapId()) &&
                this.getPosition().getY() > 0 &&
                ItemConstants.isFishingChair(chair.getChair()) &&
                this.getWorldServer().registerFisherPlayer(this, baitLevel);
    }

    public void leaveMap() {
        releaseControlledMonsters();
        visibleMapObjects.clear();
        chair.clearChair();
        if (hpDecreaseTask != null) {
            hpDecreaseTask.cancel(false);
        }

        pq.leaveArenaIfPresent();
    }

    int getChangedJobSp(JobEnum newJob) {    // 包内可见：CharacterJob.changeJob 调用
        int curSp = getUsedSp(newJob) + getJobRemainingSp(newJob);
        int spGain = 0;
        int expectedSp = getJobLevelSp(level.getLevel() - 10, newJob, GameConstants.getJobBranch(newJob));
        if (curSp < expectedSp) {
            spGain += (expectedSp - curSp);
        }

        return getSpGain(spGain, curSp, newJob);
    }

    int getUsedSp(JobEnum job) {    // 包内可见：CharacterLevel.levelUpGainSp 调用
        int jobId = job.getId();
        int spUsed = 0;

        for (Entry<Skill, SkillEntry> s : this.getSkills().entrySet()) {
            Skill skill = s.getKey();
            if (GameConstants.isInJobTree(skill.getId(), jobId) && !skill.isBeginnerSkill()) {
                spUsed += s.getValue().skillLevel;
            }
        }

        return spUsed;
    }

    private int getJobLevelSp(int level, JobEnum job, int jobBranch) {
        if (JobEnum.getJobStyleInternal(job.getId(), (byte) 0x40) == JobEnum.MAGICIAN) {
            level += 2;  // starts earlier, level 8
        }

        return 3 * level + GameConstants.getChangeJobSpUpgrade(jobBranch);
    }

    int getJobMaxSp(JobEnum job) {    // 包内可见：CharacterLevel.levelUpGainSp 调用
        int jobBranch = GameConstants.getJobBranch(getJob());
        int jobRange = GameConstants.getJobUpgradeLevelRange(jobBranch);
        return getJobLevelSp(jobRange, job, jobBranch);
    }

    int getJobRemainingSp(JobEnum job) {    // 包内可见：CharacterLevel.levelUpGainSp 调用
        return getRemainingSp(job.getId());
    }

    int getSpGain(int spGain, JobEnum job) {    // 包内可见：CharacterLevel.levelUpGainSp 调用
        int curSp = getUsedSp(job) + getJobRemainingSp(job);
        return getSpGain(spGain, curSp, job);
    }

    private int getSpGain(int spGain, int curSp, JobEnum job) {
        int maxSp = getJobMaxSp(job);
        return Math.min(spGain, maxSp - curSp);
    }

    // getHpMpGainFromRange 和 getBasicLevelUpHpMp 已迁移到 CharacterStats

    public static Character loadCharacterEntryFromDB(ResultSet rs, List<Item> equipped) {
        Character ret = new Character();

        try {
            ret.accountId = rs.getInt("accountid");
            ret.id = rs.getInt("id");
            ret.name = rs.getString("name");
            ret.gender = rs.getInt("gender");
            ret.salon.setSkinColor(SkinColor.getById(rs.getInt("skincolor")));
            ret.salon.setFace(rs.getInt("face"));
            ret.salon.setHair(rs.getInt("hair"));

            // skipping pets, probably unneeded here

            ret.level.setLevel(rs.getInt("level"));
            // job 仅从 character_json 恢复（applyData），character 表 job 列为冗余双写
            ret.applyData(CharacterData.deserialize(rs.getString("stats_json")));
            ret.level.setExp(rs.getInt("exp"));
            ret.fame.setFame(rs.getInt("fame"));
            ret.level.setGachaExp(rs.getInt("gachaexp"));
            // mapId 仅从 character_json 恢复（applyData），character 表 map 列为冗余双写
            ret.initialSpawnPoint = rs.getInt("spawnpoint");
            ret.gm.setGMLevel(rs.getInt("gm"));
            ret.world = rs.getByte("world");
            ret.rank = rs.getInt("rank");
            ret.rankMove = rs.getInt("rankMove");
            ret.jobRank = rs.getInt("jobRank");
            ret.jobRankMove = rs.getInt("jobRankMove");

            if (equipped != null) {  // players can have no equipped items at all, ofc
                Inventory inv = ret.inventory.getInventory(InventoryType.EQUIPPED);
                for (Item item : equipped) {
                    inv.addItemFromDB(item);
                }
            }
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }

        return ret;
    }

    public Character generateCharacterEntry() {
        Character ret = new Character();

        ret.accountId = this.getAccountId();
        ret.id = this.getId();
        ret.name = this.getName();
        ret.gender = this.getGender();
        ret.salon.setSkinColor(this.getSkinColor());
        ret.salon.setFace(this.getFace());
        ret.salon.setHair(this.getHair());

        // skipping pets, probably unneeded here

        ret.level.setLevel(this.getLevel());
        ret.setJob(this.getJob());
        // 快照不可变（caller guarantee：写路径 copy-on-write，旧快照发布后不再修改）——
        // 直接复制引用即原子拿到一致视图；逐条 getXxx 复制会跨快照读到不一致
        ret.stats.snapshot = this.stats.snapshot;
        ret.setRemainingSp(this.getRemainingSps());
        ret.level.setExp(this.getExp());
        ret.fame.setFame(this.getFame());
        ret.level.setGachaExp(this.getGachaExp());
        ret.setMapId(this.getMapId());
        ret.initialSpawnPoint = this.getInitialSpawnPoint();

        ret.inventory.inventories[InventoryType.EQUIPPED.ordinal()] = this.getInventory(InventoryType.EQUIPPED);

        ret.gm.setGMLevel(this.gmLevel());
        ret.world = this.getWorld();
        ret.rank = this.getRank();
        ret.rankMove = this.getRankMove();
        ret.jobRank = this.getJobRank();
        ret.jobRankMove = this.getJobRankMove();

        return ret;
    }

    public int getRemainingSp() {
        return getRemainingSp(job.getId()); //default
    }

    public void updateRemainingSp(int remainingSp) {
        updateRemainingSp(remainingSp, job.getId());
    }

    public static Character fromCharactersDO(CharactersDO charactersDO, Client client) {
        Character chr = new Character();
        chr.setClient(client);
        chr.setId(charactersDO.getId());

        chr.setName(charactersDO.getName());
        chr.level.setLevel(charactersDO.getLevel());
        chr.fame.setFame(charactersDO.getFame());
        chr.quests.setQuestFame(charactersDO.getFquest());
        loadDataFromJson(chr, charactersDO.getId());
        chr.level.setExp(charactersDO.getExp());
        chr.level.setGachaExp(charactersDO.getGachaexp());
        chr.setHasMerchant(charactersDO.getHasmerchant());
        chr.setMeso(charactersDO.getMeso());
        chr.setMerchantMeso(charactersDO.getMerchantmesos());
        chr.gm.setGMLevel(charactersDO.getGm());
        chr.salon.setSkinColor(SkinColor.getById(charactersDO.getSkincolor()));
        chr.setGender(charactersDO.getGender());
        // job 仅从 character_json 恢复（applyData），character 表 job 列为冗余双写
        chr.setFinishedDojoTutorial(charactersDO.getFinishedDojoTutorial() == 1);
        chr.setVanquisherKills(charactersDO.getVanquisherKills());
        chr.miniGame.setOmokwins(charactersDO.getOmokwins());
        chr.miniGame.setOmoklosses(charactersDO.getOmoklosses());
        chr.miniGame.setOmokties(charactersDO.getOmokties());
        chr.miniGame.setMatchcardwins(charactersDO.getMatchcardwins());
        chr.miniGame.setMatchcardlosses(charactersDO.getMatchcardlosses());
        chr.miniGame.setMatchcardties(charactersDO.getMatchcardties());
        chr.salon.setHair(charactersDO.getHair());
        chr.salon.setFace(charactersDO.getFace());
        chr.setAccountId(charactersDO.getAccountid());
        // mapId 仅从 character_json 恢复（applyData），character 表 map 列为冗余双写
        chr.setInitialSpawnPoint(charactersDO.getSpawnpoint());
        chr.setWorld(charactersDO.getWorld());
        chr.setRank(charactersDO.getRank());
        chr.setRankMove(charactersDO.getRankMove());
        chr.setJobRank(charactersDO.getJobRank());
        chr.setJobRankMove(charactersDO.getJobRankMove());
        chr.guild.setGuildId(charactersDO.getGuildid());
        chr.guild.setGuildRank(charactersDO.getGuildrank());
        chr.guild.setAllianceRank(charactersDO.getAllianceRank());
        chr.family.setFamilyId(charactersDO.getFamilyId());
        chr.setBookCover(charactersDO.getMonsterbookcover());
        chr.setMonsterBook(new MonsterBook(charactersDO.getId()));
        chr.setVanquisherStage(charactersDO.getVanquisherStage());
        chr.pq.setAriantPoints(charactersDO.getAriantPoints());
        chr.setDojoPoints(charactersDO.getDojoPoints());
        chr.setDojoStage(charactersDO.getLastDojoStage());
        chr.pq.setDataString(charactersDO.getDataString());
        chr.guild.setMGC(new GuildCharacter(chr));
        chr.buddy.setBuddylist(new BuddyList(charactersDO.getBuddyCapacity()));
        chr.lastExpGainTime = charactersDO.getLastExpGainTime().getTime();
        chr.party.setCanRecvPartySearchInvite(charactersDO.getPartySearch());
        chr.getInventory(InventoryType.EQUIP).setSlotLimit(charactersDO.getEquipslots());
        chr.getInventory(InventoryType.USE).setSlotLimit(charactersDO.getUseslots());
        chr.getInventory(InventoryType.SETUP).setSlotLimit(charactersDO.getSetupslots());
        chr.getInventory(InventoryType.ETC).setSlotLimit(charactersDO.getEtcslots());
        short sandboxCheck = 0x0;
        for (InventoryType inventoryType : InventoryType.values()) {
            List<InventorySearchRtnDTO> searchRtnDTOList = inventoryService.getInventoryList(InventorySearchReqDTO.builder()
                    .characterId(charactersDO.getId())
                    .inventoryType(inventoryType.getType())
                    .build());
            for (InventorySearchRtnDTO searchRtnDTO : searchRtnDTOList) {
                sandboxCheck |= searchRtnDTO.getFlag();
                Item item = searchRtnDTO.toItem();
                chr.getInventory(inventoryType).addItemFromDB(item);
                if (item.getPetId() > -1) {
                    Pet pet = item.getPet();
                    if (pet != null && pet.isSummoned()) {
                        chr.addPet(pet);
                        // 登录时对已召唤宠物统一走同一套过滤配置加载逻辑，避免后续入口行为不一致。
                        chr.loadPetExcludedItems(item.getPetId());
                    }
                    continue;
                }
                if (searchRtnDTO.isEquipment() && searchRtnDTO.getInventoryEquipment().getRingId() > -1) {
                    Ring ring = Ring.loadFromDb(searchRtnDTO.getInventoryEquipment().getRingId());
                    if (ring == null) {
                        continue;
                    }
                    if (InventoryType.EQUIPPED.equals(inventoryType)) {
                        ring.equip();
                    }
                    chr.addPlayerRing(ring);
                }
            }
        }
        chr.commitExcludedItems();
        if ((sandboxCheck & ItemConstants.SANDBOX) == ItemConstants.SANDBOX) {
            chr.setHasSandboxItem();
        }
        chr.marriage.setPartnerId(charactersDO.getPartnerId());
        chr.marriage.setMarriageItemId(charactersDO.getMarriageItemId());
        World world = Server.getInstance().getWorld(charactersDO.getWorld());
        if (charactersDO.getMarriageItemId() > 0 && charactersDO.getPartnerId() <= 0) {
            chr.marriage.setMarriageItemId(-1);
        } else if (charactersDO.getPartnerId() > 0 && world.getRelationshipId(charactersDO.getId()) <= 0) {
            chr.marriage.setMarriageItemId(-1);
            chr.marriage.setPartnerId(-1);
        }
        NewYearCardRecord.loadPlayerNewYearCards(chr);

        List<TrocklocationsDO> trocklocationsDOList = characterService.getTrockLocationByCharacter(charactersDO.getId());
        int vip = 0;
        int reg = 0;
        for (int i = 0; i < 15; i++) {
            if (i < trocklocationsDOList.size()) {
                TrocklocationsDO trocklocationsDO = trocklocationsDOList.get(i);
                if (trocklocationsDO.getVip() == 1) {
                    vip++;
                    chr.getVipTrockMaps().add(trocklocationsDO.getMapid());
                } else {
                    reg++;
                    chr.getTrockMaps().add(trocklocationsDO.getMapid());
                }
                continue;
            }
            if (vip < 10) {
                chr.getVipTrockMaps().add(MapId.NONE);
            }
            if (reg < 5) {
                chr.getTrockMaps().add(MapId.NONE);
            }
        }

        AccountsDO accountsDO = accountService.findById(charactersDO.getAccountid());
        chr.getClient().setAccountName(accountsDO.getName());
        chr.getClient().setCharacterSlots(Optional.ofNullable(accountsDO.getCharacterslots()).map(Integer::byteValue).orElse((byte) 0));
        chr.getClient().setLanguage(accountsDO.getLanguage());

        List<AreaInfoDO> areaInfoDOList = characterService.getAreaInfoByCharacter(charactersDO.getId());
        areaInfoDOList.forEach(areaInfoDO -> chr.getAreaInfos().put(Optional.ofNullable(areaInfoDO.getArea()).map(Integer::shortValue).orElse((short) 0),
                areaInfoDO.getInfo()));

        List<EventstatsDO> eventstatsDOList = characterService.getEventStatsByCharacter(charactersDO.getId());
        eventstatsDOList.forEach(eventstatsDO -> chr.getEvents().put(eventstatsDO.getName(), new RescueGaga(Optional.ofNullable(eventstatsDO.getInfo()).orElse(0))));

        chr.setCashShop(new CashShop(charactersDO.getAccountid(), charactersDO.getId(), chr.getJobType()));
        chr.setAutoBanManager(new AutobanManager(chr));

        List<CharactersDO> charactersDOList = characterService.getCharacterByAccountId(charactersDO.getAccountid());
        charactersDOList.stream()
                .filter(chrDO -> !Objects.equals(chrDO.getId(), charactersDO.getId()))
                .max(Comparator.comparing(CharactersDO::getLevel))
                .ifPresent(chrDO -> {
                    chr.setLinkedName(chrDO.getName());
                    chr.setLinkedLevel(chrDO.getLevel());
                });

        int mountId = chr.getJobType() * 10000000 + 1004;
        if (chr.getInventory(InventoryType.EQUIPPED).getItem((short) -18) != null) {
            chr.setMapleMount(new Mount(chr, chr.getInventory(InventoryType.EQUIPPED).getItem((short) -18).getItemId(), mountId));
        } else {
            chr.setMapleMount(new Mount(chr, 0, mountId));
        }
        chr.getMapleMount().setExp(charactersDO.getMountexp());
        chr.getMapleMount().setLevel(charactersDO.getMountlevel());
        chr.getMapleMount().setTiredness(charactersDO.getMounttiredness());
        chr.getMapleMount().setActive(false);
        QuickslotkeymappedDO quickSlotKeyMap = accountService.getQuickSlotKeyMap(charactersDO.getAccountid());
        if (quickSlotKeyMap != null) {
            chr.keybinding.setQuickSlotLoaded(NumberTool.LongToBytes(quickSlotKeyMap.getKeymap()));
            chr.keybinding.setQuickSlotKeyMapped(new QuickslotBinding(chr.keybinding.getQuickSlotLoaded()));
        }
        return chr;
    }

    // ── 持久化数据转换：CharacterData 信封在此组装/应用，各数据域的映射由对应模块完成。
    //    持久化代码（JDBC）只接触 CharacterData，不直接看到 CharacterStatsData 等域类型 ──

    public CharacterData toData() {
        CharacterData data = new CharacterData(stats.toData());
        data.skills = skills.toData();
        data.ap = ap.toData();
        data.sp = sp.toData();
        data.debuffs = debuffs.toData();
        data.antiCheat = antiCheat.toData();
        data.jobId = job.getId();
        data.mapId = getMapId();
        data.timestamp = Server.getInstance().getCurrentTime();
        return data;
    }

    public void applyData(CharacterData data) {
        stats.applyData(data.stats);
        skills.applyData(data.skills);
        ap.applyData(data.ap);
        sp.applyData(data.sp);
        debuffs.applyData(data.debuffs, data.timestamp);
        antiCheat.applyData(data.antiCheat);
        job.setJob(JobEnum.getById(data.jobId));
        map.setMapId(data.mapId);
    }

    // stats/skills 等域均存于 character_json，此处加载整个信封
    private static void loadDataFromJson(Character chr, int cid) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT data FROM character_json WHERE id = ?")) {
            ps.setInt(1, cid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    chr.applyData(CharacterData.deserialize(rs.getString("data")));
                    return;
                }
            }
        } catch (SQLException e) {
            log.error("加载 character_json 失败, cid={}", cid, e);
        }
        throw new IllegalStateException("character_json 缺少角色属性数据, cid=" + cid);
    }

    public static Character loadCharFromDB(final int cid, Client client, boolean channelServer) {
        try {
            return characterService.loadCharFromDB(cid, client, channelServer);
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Character.loadCharFromDB.error1"), cid, e);
        }
        return null;
    }

    public static String makeMapleReadable(String in) {
        return in.replace('I', 'i')
                .replace('l', 'L')
                .replace("rn", "Rn")
                .replace("vv", "Vv")
                .replace("VV", "Vv");
    }

    public void message(String m) {
        dropMessage(5, m);
    }

    public void yellowMessage(String m) {
        sendPacket(PacketCreator.sendYellowTip(m));
    }

    public Mount mount(int id, int skillid) {
        Mount mount = mapleMount;
        mount.setItemId(id);
        mount.setSkillId(skillid);
        return mount;
    }

    void prepareDragonBlood(final BuffEffectData bloodEffect) {
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
        }
        dragonBloodSchedule = TimerManager.getInstance().register(() -> {
            if (awayFromWorld.get()) {
                return;
            }

            addHP(-bloodEffect.getX());
            sendPacket(PacketCreator.showOwnBuffEffect(bloodEffect.getSourceId(), 5));
            getMap().broadcastMessage(Character.this, PacketCreator.showBuffEffect(getId(), bloodEffect.getSourceId(), 5), false);
        }, 4000, 4000);
    }

    public void removeVisibleMapObject(MapObject mo) {
        visibleMapObjects.remove(mo);
    }

    public synchronized void resetStats() {
        if (!GameConfig.getServerBoolean("use_auto_assign_starters_ap")) {
            return;
        }

        // effLock 已冗余：reset 仅动 stats/ap + applyUpdateSilently
        try (var ignored = Locks.acquire(stats.wLock)) {
            int tap = ap.getRemainingAp() + stats.getBase(STR) + stats.getBase(DEX) + stats.getBase(INT) + stats.getBase(LUK), tsp = 1;
            int tstr = 4, tdex = 4, tint = 4, tluk = 4;

            switch (job.getId()) {
                case 100:
                case 1100:
                case 2100:
                    tstr = 35;
                    tsp += ((getLevel() - 10) * 3);
                    break;
                case 200:
                case 1200:
                    tint = 20;
                    tsp += ((getLevel() - 8) * 3);
                    break;
                case 300:
                case 1300:
                case 400:
                case 1400:
                    tdex = 25;
                    tsp += ((getLevel() - 10) * 3);
                    break;
                case 500:
                case 1500:
                    tdex = 20;
                    tsp += ((getLevel() - 10) * 3);
                    break;
            }

            tap -= tstr;
            tap -= tdex;
            tap -= tint;
            tap -= tluk;

            if (tap >= 0) {
                // 属性与 SP 分两次静默应用，拼装变更集后一次性公告
                Map<PacketStat, Integer> statUpdates = stats.update()
                        .set(STR, tstr)
                        .set(DEX, tdex)
                        .set(INT, tint)
                        .set(LUK, tluk)
                        .setAp(tap)
                        .commitSilently();
                statUpdates.put(PacketStat.AVAILABLESP, sp.changeRemainingSp(tsp, job.getId(), true));
                stats.announceStatsUpdate(statUpdates);
            } else {
                log.warn("Chr {} tried to have its stats reset without enough AP available", getName());
            }
        }
    }

    public void resetEnteredScript() {
        entered.remove(getMap().getId());
    }

    public void resetEnteredScript(int mapId) {
        entered.remove(mapId);
    }

    public void resetEnteredScript(String script) {
        for (int mapId : entered.keySet()) {
            if (entered.get(mapId).equals(script)) {
                entered.remove(mapId);
            }
        }
    }

    public void saveLocationOnWarp() {  // suggestion to remember the map before warp command thanks to Lei
        Portal closest = getMap().findClosestPortal(getPosition());
        int curMapid = getMapId();

        for (int i = 0; i < savedLocations.length; i++) {
            if (savedLocations[i] == null) {
                savedLocations[i] = new SavedLocation(curMapid, closest != null ? closest.getId() : 0);
            }
        }
    }

    public void saveLocation(String type) {
        Portal closest = getMap().findClosestPortal(getPosition());
        savedLocations[SavedLocationType.fromString(type).ordinal()] = new SavedLocation(getMapId(), closest != null ? closest.getId() : 0);
    }

    public final boolean insertNewChar(CharacterFactoryRecipe recipe) {
        stats.update()
                .set(STR, recipe.getStr())
                .set(DEX, recipe.getDex())
                .set(INT, recipe.getInt())
                .set(LUK, recipe.getLuk())
                .set(MAX_HP, recipe.getMaxHp())
                .set(MAX_MP, recipe.getMaxMp())
                .setHp(recipe.getMaxHp())
                .setMp(recipe.getMaxMp())
                .setAp(recipe.getRemainingAp())
                .commitSilently();
        level.setLevel(recipe.getLevel());
        sp.remainingSp[CharacterSp.indexOf(job.getId())] = recipe.getRemainingSp();
        setMapId(recipe.getMap());
        meso.set(recipe.getMeso());

        List<Pair<Skill, Integer>> startingSkills = recipe.getStartingSkillLevel();
        for (Pair<Skill, Integer> skEntry : startingSkills) {
            Skill skill = skEntry.getLeft();
            this.changeSkillLevel(skill, skEntry.getRight().byteValue(), skill.getMaxLevel(), -1);
        }

        List<Pair<Item, InventoryType>> itemsWithType = recipe.getStartingItems();
        for (Pair<Item, InventoryType> itEntry : itemsWithType) {
            this.getInventory(itEntry.getRight()).addItem(itEntry.getLeft());
        }

        this.events.put("rescueGaga", new RescueGaga(0));

        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);

            try {
                // Character info
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO characters (gm, skincolor, gender, job, hair, face, meso, spawnpoint, accountid, name, world, level) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, gm.gmLevel());
                    ps.setInt(2, salon.getSkinColor().getId());
                    ps.setInt(3, gender);
                    ps.setInt(4, job.getId());
                    ps.setInt(5, salon.getHair());
                    ps.setInt(6, salon.getFace());
                    ps.setInt(7, Math.abs(meso.get()));
                    ps.setInt(8, 0);
                    ps.setInt(9, accountId);
                    ps.setString(10, name);
                    ps.setInt(11, world);
                    ps.setInt(12, level.getLevel());

                    int updateRows = ps.executeUpdate();
                    if (updateRows < 1) {
                        log.error("Error trying to insert chr {}", name);
                        return false;
                    }

                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            this.id = rs.getInt(1);
                        } else {
                            log.error("Inserting chr {} failed", name);
                            return false;
                        }
                    }
                }

                // CharacterStats 持久化为 JSON
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO character_json (id, data) VALUES (?, ?)")) {
                    ps.setInt(1, id);
                    ps.setString(2, toData().serialize());
                    ps.executeUpdate();
                }

                // Select a keybinding method
                int[] selectedKey;
                int[] selectedType;
                int[] selectedAction;

                if (GameConfig.getServerBoolean("use_custom_keyset")) {
                    selectedKey = GameConstants.getCustomKey(true);
                    selectedType = GameConstants.getCustomType(true);
                    selectedAction = GameConstants.getCustomAction(true);
                } else {
                    selectedKey = GameConstants.getCustomKey(false);
                    selectedType = GameConstants.getCustomType(false);
                    selectedAction = GameConstants.getCustomAction(false);
                }

                // Key config
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO keymap (characterid, `key`, `type`, `action`) VALUES (?, ?, ?, ?)")) {
                    ps.setInt(1, id);
                    for (int i = 0; i < selectedKey.length; i++) {
                        ps.setInt(2, selectedKey[i]);
                        ps.setInt(3, selectedType[i]);
                        ps.setInt(4, selectedAction[i]);
                        ps.executeUpdate();
                    }
                }

                // No quickslots, or no change.
                boolean bQuickslotEquals = this.keybinding.getQuickSlotKeyMapped() == null || (this.keybinding.getQuickSlotLoaded() != null && Arrays.equals(this.keybinding.getQuickSlotKeyMapped().GetKeybindings(), this.keybinding.getQuickSlotLoaded()));
                if (!bQuickslotEquals) {
                    long nQuickslotKeymapped = NumberTool.BytesToLong(this.keybinding.getQuickSlotKeyMapped().GetKeybindings());

                    // Quickslot key config
                    try (PreparedStatement ps = con.prepareStatement("INSERT INTO quickslotkeymapped (accountid, keymap) VALUES (?, ?) ON CONFLICT(accountid) DO UPDATE SET keymap = ?;")) {
                        ps.setInt(1, this.getAccountId());
                        ps.setLong(2, nQuickslotKeymapped);
                        ps.setLong(3, nQuickslotKeymapped);
                        ps.executeUpdate();
                    }
                }

                itemsWithType = new ArrayList<>();
                for (Inventory iv : inventory.getInventories()) {
                    for (Item item : iv.list()) {
                        itemsWithType.add(new Pair<>(item, iv.getType()));
                    }
                }

                ItemFactory.INVENTORY.saveItems(itemsWithType, id, con);

                con.commit();
                return true;
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                con.setAutoCommit(true);
            }
        } catch (Throwable t) {
            log.error("Error creating chr {}, level: {}, job: {}", name, level.getLevel(), job.getId(), t);
        }

        return false;
    }

    public void saveCharToDB() {
        if (GameConfig.getServerBoolean("use_autosave")) {
            Runnable r = () -> saveCharToDB(true);

            CharacterSaveService service = getCharacterSaveService();
            service.registerSaveCharacter(this.getId(), r);
        } else {
            saveCharToDB(true);
        }
    }

    private CharacterSaveService getCharacterSaveService() {
        return (CharacterSaveService) getWorldServer().getServiceAccess(WorldServices.SAVE_CHARACTER);
    }

    //ItemFactory saveItems and monsterbook.saveCards are the most time consuming here.
    public synchronized void saveCharToDB(boolean notAutosave) {
        if (!loggedIn) {
            // 如果已经退出登录，取消自动保存当前角色任务
            CharacterSaveService service = getCharacterSaveService();
            service.unregisterSaveCharacter(this.getId());
            return;
        }

        log.info(I18nUtil.getLogMessage(notAutosave ? "Character.saveCharToDB.info1" : "Character.saveCharToDB.info2"), name);

        Server.getInstance().updateCharacterEntry(this);

        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);

            try {
                String statsJson;

                try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET level = ?, fame = ?, exp = ?, gachaexp = ?, gm = ?, skincolor = ?, gender = ?, job = ?, hair = ?, face = ?, meso = ?, spawnpoint = ?, party = ?, buddyCapacity = ?, messengerid = ?, messengerposition = ?, mountlevel = ?, mountexp = ?, mounttiredness= ?, equipslots = ?, useslots = ?, setupslots = ?, etcslots = ?,  monsterbookcover = ?, vanquisherStage = ?, dojoPoints = ?, lastDojoStage = ?, finishedDojoTutorial = ?, vanquisherKills = ?, matchcardwins = ?, matchcardlosses = ?, matchcardties = ?, omokwins = ?, omoklosses = ?, omokties = ?, dataString = ?, fquest = ?, partnerId = ?, marriageItemId = ?, lastExpGainTime = ?, ariantPoints = ?, partySearch = ? WHERE id = ?", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, level.getLevel());    // thanks CanIGetaPR for noticing an unnecessary "level" limitation when persisting DB data
                    ps.setInt(2, fame.getFame());

                    try (var ignored = Locks.acquire(stats.wLock)) {   // 仅序列化 stats + 读原子字段
                        statsJson = toData().serialize();

                        ps.setInt(3, Math.abs(level.getExp()));
                        ps.setInt(4, Math.abs(level.getGachaExp()));
                    }

                    ps.setInt(5, gm.gmLevel());
                    ps.setInt(6, salon.getSkinColor().getId());
                    ps.setInt(7, gender);
                    ps.setInt(8, job.getId());
                    ps.setInt(9, salon.getHair());
                    ps.setInt(10, salon.getFace());
                    ps.setInt(11, meso.get());
                    if (getMap() == null || getMap().getId() == MapId.CRIMSONWOOD_VALLEY_1 || getMap().getId() == MapId.CRIMSONWOOD_VALLEY_2) {  // reset to first spawnpoint on those maps
                        ps.setInt(12, 0);
                    } else {
                        Portal closest = getMap().findClosestPlayerSpawnpoint(getPosition());
                        if (closest != null) {
                            ps.setInt(12, closest.getId());
                        } else {
                            ps.setInt(13, 0);
                        }
                    }

                    try (var ignored = Locks.acquire(party.lock)) {
                        if (party.party != null) {
                            ps.setInt(13, party.party.getId());
                        } else {
                            ps.setInt(13, -1);
                        }
                    }

                    ps.setInt(14, buddy.getBuddylist().getCapacity());
                    if (messenger != null) {
                        ps.setInt(15, messenger.getId());
                        ps.setInt(16, messengerPosition);
                    } else {
                        ps.setInt(15, 0);
                        ps.setInt(16, 4);
                    }
                    if (mapleMount != null) {
                        ps.setInt(17, mapleMount.getLevel());
                        ps.setInt(18, mapleMount.getExp());
                        ps.setInt(19, mapleMount.getTiredness());
                    } else {
                        ps.setInt(17, 1);
                        ps.setInt(18, 0);
                        ps.setInt(19, 0);
                    }
                    for (int i = 1; i < 5; i++) {
                        ps.setInt(i + 19, getSlots(i));
                    }

                    monsterBook.saveCards(con, id);

                    ps.setInt(24, bookCover);
                    ps.setInt(25, vanquisherStage);
                    ps.setInt(26, dojoPoints);
                    ps.setInt(27, dojoStage);
                    ps.setInt(28, finishedDojoTutorial ? 1 : 0);
                    ps.setInt(29, vanquisherKills);
                    ps.setInt(30, miniGame.getMatchcardwins());
                    ps.setInt(31, miniGame.getMatchcardlosses());
                    ps.setInt(32, miniGame.getMatchcardties());
                    ps.setInt(33, miniGame.getOmokwins());
                    ps.setInt(34, miniGame.getOmoklosses());
                    ps.setInt(35, miniGame.getOmokties());
                    ps.setString(36, pq.getDataString());
                    ps.setInt(37, quests.getQuestFame());
                    ps.setInt(38, marriage.getPartnerId());
                    ps.setInt(39, marriage.getMarriageItemId());
                    ps.setTimestamp(40, new Timestamp(lastExpGainTime));
                    ps.setInt(41, pq.getAriantPoints());
                    ps.setBoolean(42, party.canRecvPartySearchInvite);
                    ps.setInt(43, id);

                    int updateRows = ps.executeUpdate();
                    if (updateRows < 1) {
                        throw new RuntimeException("Character not in database (" + id + ")");
                    }
                }

                // CharacterStats 持久化为 JSON（str/dex/int/luk/hp/mp/maxHp/maxMp 已从 characters 表移除）
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO character_json (id, data) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET data = excluded.data")) {
                    ps.setInt(1, id);
                    ps.setString(2, statsJson);
                    ps.executeUpdate();
                }

                pets.saveToDb(con);   // 并入主事务连接，消除第二写者（SQLITE_BUSY）

                // Key config
                deleteWhereCharacterId(con, "DELETE FROM keymap WHERE characterid = ?");
                try (PreparedStatement psKey = con.prepareStatement("INSERT INTO keymap (characterid, `key`, `type`, `action`) VALUES (?, ?, ?, ?)")) {
                    psKey.setInt(1, id);

                    Set<Entry<Integer, KeyBinding>> keybindingItems = Collections.unmodifiableSet(keybinding.getKeymap().entrySet());
                    for (Entry<Integer, KeyBinding> keybinding : keybindingItems) {
                        psKey.setInt(2, keybinding.getKey());
                        psKey.setInt(3, keybinding.getValue().getType());
                        psKey.setInt(4, keybinding.getValue().getAction());
                        psKey.addBatch();
                    }
                    psKey.executeBatch();
                }

                // No quickslots, or no change.
                boolean bQuickslotEquals = this.keybinding.getQuickSlotKeyMapped() == null || (this.keybinding.getQuickSlotLoaded() != null && Arrays.equals(this.keybinding.getQuickSlotKeyMapped().GetKeybindings(), this.keybinding.getQuickSlotLoaded()));
                if (!bQuickslotEquals) {
                    long nQuickslotKeymapped = NumberTool.BytesToLong(this.keybinding.getQuickSlotKeyMapped().GetKeybindings());

                    try (final PreparedStatement psQuick = con.prepareStatement("INSERT INTO quickslotkeymapped (accountid, keymap) VALUES (?, ?) ON CONFLICT(accountid) DO UPDATE SET keymap = ?;")) {
                        psQuick.setInt(1, this.getAccountId());
                        psQuick.setLong(2, nQuickslotKeymapped);
                        psQuick.setLong(3, nQuickslotKeymapped);
                        psQuick.executeUpdate();
                    }
                }

                // Skill macros
                deleteWhereCharacterId(con, "DELETE FROM skillmacros WHERE characterid = ?");
                try (PreparedStatement psMacro = con.prepareStatement("INSERT INTO skillmacros (characterid, skill1, skill2, skill3, name, shout, position) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    psMacro.setInt(1, getId());
                    for (int i = 0; i < 5; i++) {
                        SkillMacro macro = skillMacros[i];
                        if (macro != null) {
                            psMacro.setInt(2, macro.getSkill1());
                            psMacro.setInt(3, macro.getSkill2());
                            psMacro.setInt(4, macro.getSkill3());
                            psMacro.setString(5, macro.getName());
                            psMacro.setInt(6, macro.getShout());
                            psMacro.setInt(7, i);
                            psMacro.addBatch();
                        }
                    }
                    psMacro.executeBatch();
                }

                List<Pair<Item, InventoryType>> itemsWithType = new ArrayList<>();
                for (Inventory iv : inventory.getInventories()) {
                    for (Item item : iv.list()) {
                        itemsWithType.add(new Pair<>(item, iv.getType()));
                    }
                }

                // Items
                ItemFactory.INVENTORY.saveItems(itemsWithType, id, con);

                // Saved locations
                deleteWhereCharacterId(con, "DELETE FROM savedlocations WHERE characterid = ?");
                try (PreparedStatement psLoc = con.prepareStatement("INSERT INTO savedlocations (characterid, `locationtype`, `map`, `portal`) VALUES (?, ?, ?, ?)")) {
                    psLoc.setInt(1, id);
                    for (SavedLocationType savedLocationType : SavedLocationType.values()) {
                        if (savedLocations[savedLocationType.ordinal()] != null) {
                            psLoc.setString(2, savedLocationType.name());
                            psLoc.setInt(3, savedLocations[savedLocationType.ordinal()].getMapId());
                            psLoc.setInt(4, savedLocations[savedLocationType.ordinal()].getPortal());
                            psLoc.addBatch();
                        }
                    }
                    psLoc.executeBatch();
                }

                deleteWhereCharacterId(con, "DELETE FROM trocklocations WHERE characterid = ?");

                // Vip teleport rocks
                try (PreparedStatement psVip = con.prepareStatement("INSERT INTO trocklocations(characterid, mapid, vip) VALUES (?, ?, 0)")) {
                    for (int i = 0; i < getTrockSize(); i++) {
                        if (trockmaps.get(i) != MapId.NONE) {
                            psVip.setInt(1, getId());
                            psVip.setInt(2, trockmaps.get(i));
                            psVip.addBatch();
                        }
                    }
                    psVip.executeBatch();
                }

                // Regular teleport rocks
                try (PreparedStatement psReg = con.prepareStatement("INSERT INTO trocklocations(characterid, mapid, vip) VALUES (?, ?, 1)")) {
                    for (int i = 0; i < getVipTrockSize(); i++) {
                        if (viptrockmaps.get(i) != MapId.NONE) {
                            psReg.setInt(1, getId());
                            psReg.setInt(2, viptrockmaps.get(i));
                            psReg.addBatch();
                        }
                    }
                    psReg.executeBatch();
                }

                // Buddy
                deleteWhereCharacterId(con, "DELETE FROM buddies WHERE characterid = ? AND pending = 0");
                try (PreparedStatement psBuddy = con.prepareStatement("INSERT INTO buddies (characterid, `buddyid`, `pending`, `group`) VALUES (?, ?, 0, ?)")) {
                    psBuddy.setInt(1, id);

                    for (BuddylistEntry entry : buddy.getBuddylist().getBuddies()) {
                        if (entry.isVisible()) {
                            psBuddy.setInt(2, entry.getCharacterId());
                            psBuddy.setString(3, entry.getGroup());
                            psBuddy.addBatch();
                        }
                    }
                    psBuddy.executeBatch();
                }

                // Area info
                deleteWhereCharacterId(con, "DELETE FROM area_info WHERE charid = ?");
                try (PreparedStatement psArea = con.prepareStatement("INSERT INTO area_info (id, charid, area, info) VALUES (NULL, ?, ?, ?)")) {
                    psArea.setInt(1, id);

                    for (Entry<Short, String> area : area_info.entrySet()) {
                        psArea.setInt(2, area.getKey());
                        psArea.setString(3, area.getValue());
                        psArea.addBatch();
                    }
                    psArea.executeBatch();
                }

                // Event stats
                if (events != null) {
                    deleteWhereCharacterId(con, "DELETE FROM eventstats WHERE characterid = ?");
                    try (PreparedStatement psEvent = con.prepareStatement("INSERT INTO eventstats (characterid, name, info) VALUES (?, ?, ?)")) {
                        psEvent.setInt(1, id);
                        for (Map.Entry<String, Events> entry : events.entrySet()) {
                            psEvent.setString(2, entry.getKey());
                            psEvent.setInt(3, entry.getValue().getInfo());
                            psEvent.addBatch();
                        }
                        psEvent.executeBatch();
                    }
                }

                CharacterQuests.deleteQuestProgressWhereCharacterId(con, id);

                // Quests and medals
                try (PreparedStatement psStatus = con.prepareStatement("INSERT INTO queststatus (`queststatusid`, `characterid`, `quest`, `status`, `time`, `expires`, `forfeited`, `completed`) VALUES (NULL, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement psProgress = con.prepareStatement("INSERT INTO questprogress VALUES (NULL, ?, ?, ?, ?)");
                     PreparedStatement psMedal = con.prepareStatement("INSERT INTO medalmaps VALUES (NULL, ?, ?, ?)")) {
                    psStatus.setInt(1, id);

                    for (QuestStatus qs : quests.getQuestValues()) {
                        psStatus.setInt(2, qs.getQuest().getId());
                        psStatus.setInt(3, qs.getStatus().getId());
                        psStatus.setInt(4, (int) (qs.getCompletionTime() / 1000));
                        psStatus.setLong(5, qs.getExpirationTime());
                        psStatus.setInt(6, qs.getForfeited());
                        psStatus.setInt(7, qs.getCompleted());
                        psStatus.executeUpdate();

                        try (ResultSet rs = psStatus.getGeneratedKeys()) {
                            rs.next();
                            for (int mob : qs.getProgress().keySet()) {
                                psProgress.setInt(1, id);
                                psProgress.setInt(2, rs.getInt(1));
                                psProgress.setInt(3, mob);
                                psProgress.setString(4, qs.getProgress(mob));
                                psProgress.addBatch();
                            }
                            psProgress.executeBatch();

                            for (int i = 0; i < qs.getMedalMaps().size(); i++) {
                                psMedal.setInt(1, id);
                                psMedal.setInt(2, rs.getInt(1));
                                psMedal.setInt(3, qs.getMedalMaps().get(i));
                                psMedal.addBatch();
                            }
                            psMedal.executeBatch();
                        }
                    }
                }

                FamilyEntry familyEntry = family.getFamilyEntry(); //save family rep
                if (familyEntry != null) {
                    if (familyEntry.saveReputation(con)) {
                        familyEntry.savedSuccessfully();
                    }
                    FamilyEntry senior = familyEntry.getSenior();
                    if (senior != null && senior.getChr() == null) { //only save for offline family members
                        if (senior.saveReputation(con)) {
                            senior.savedSuccessfully();
                        }
                        senior = senior.getSenior(); //save one level up as well
                        if (senior != null && senior.getChr() == null) {
                            if (senior.saveReputation(con)) {
                                senior.savedSuccessfully();
                            }
                        }
                    }

                }

                if (cashShop != null) {
                    cashShop.save(con);
                }

                if (storage.getStorage() != null && storage.getUsedStorage()) {
                    storage.getStorage().saveToDB(con);
                    storage.resetUsedStorage();
                }

                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                con.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("Error saving chr {}, level: {}, job: {}", name, level.getLevel(), job.getId(), e);
        }
    }

    public void sendMacros() {
        // Always send the macro packet to fix a client side bug when switching characters.
        sendPacket(PacketCreator.getMacros(skillMacros));
    }

    public void setChalkboard(String text) {
        this.chalktext = text;
    }

    public void setDojoEnergy(int x) {
        this.dojoEnergy = Math.min(x, 10000);
    }

    public void setEventInstance(EventInstanceManager eventInstance) {
        evtLock.lock();
        try {
            this.eventInstance = eventInstance;
        } finally {
            evtLock.unlock();
        }
    }

    public void finishDojoTutorial() {
        this.finishedDojoTutorial = true;
    }

    // ── 属性变更钩子：原 CharacterListener 的实现合并至此 ──

    /** HP/MP 池更新后的重算与钳制，返回需并入本次公告的属性修正 */

    // calcHpRatioUpdate / calcMpRatioUpdate / calcTransientRatio / calcHpRatioTransient / calcMpRatioTransient
    // 计算部分已迁移到 CharacterStats，以下是使用这些计算的编排方法

    private long getDojoTimeLeft() {
        return client.getChannelServer().getDojoFinishTime(getMap().getId()) - Server.getInstance().getCurrentTime();
    }

    public void showDojoClock() {
        if (GameConstants.isDojoBossArea(getMap().getId())) {
            sendPacket(PacketCreator.getClock((int) (getDojoTimeLeft() / 1000)));
        }
    }

    public void showUnderLeveledInfo(Monster mob) {
        long curTime = Server.getInstance().getCurrentTime();
        if (nextWarningTime < curTime) {
            nextWarningTime = curTime + MINUTES.toMillis(1);   // show underlevel info again after 1 minute

            showHint(I18nUtil.getMessage("Character.showUnderLeveledInfo", mob.getName(), mob.getLevel()));
        }
    }

    public void showMapOwnershipInfo(Character mapOwner) {
        long curTime = Server.getInstance().getCurrentTime();
        if (nextWarningTime < curTime) {
            nextWarningTime = curTime + MINUTES.toMillis(1); // show underlevel info again after 1 minute

            String medal = "";
            Item medalItem = mapOwner.getInventory(InventoryType.EQUIPPED).getItem((short) -49);
            if (medalItem != null) {
                medal = "<" + ItemInformationProvider.getInstance().getName(medalItem.getItemId()) + "> ";
            }

            List<String> strLines = new LinkedList<>();
            strLines.add("");
            strLines.add("");
            strLines.add("");
            strLines.add(this.getClient().getChannelServer().getServerMessage().isEmpty() ? 0 : 1, "Get off my lawn!!");

            this.sendPacket(PacketCreator.getAvatarMega(mapOwner, medal, this.getClient().getChannel(), ItemId.ROARING_TIGER_MESSENGER, strLines, true));
        }
    }

    public void showHint(String msg) {
        showHint(msg, 500);
    }

    public void showHint(String msg, int length) {
        client.announceHint(msg, length);
    }

    public boolean runTirednessSchedule() {
        if (mapleMount != null) {
            int tiredness = mapleMount.incrementAndGetTiredness();

            this.getMap().broadcastMessage(PacketCreator.updateMount(this.getId(), mapleMount, false));
            if (tiredness > 99) {
                mapleMount.setTiredness(99);
                this.dispelSkill(this.getJobType() * 10000000 + 1004);
                this.dropMessage(6, I18nUtil.getMessage("Character.runTirednessSchedule"));
                return false;
            }
        }

        return true;
    }

    public void startMapEffect(String msg, int itemId) {
        startMapEffect(msg, itemId, 30000);
    }

    public void startMapEffect(String msg, int itemId, int duration) {
        final MapEffect mapEffect = new MapEffect(msg, itemId);
        sendPacket(mapEffect.makeStartData());
        TimerManager.getInstance().schedule(() -> sendPacket(mapEffect.makeDestroyData()), duration);
    }

    public void updateMacros(int position, SkillMacro updateMacro) {
        skillMacros[position] = updateMacro;
    }

    public void updateSingleStat(PacketStat stat, int newval) {
        updateSingleStat(stat, newval, false);
    }

    private void updateSingleStat(PacketStat stat, int newval, boolean itemReaction) {
        sendPacket(PacketCreator.updatePlayerStats(Collections.singletonList(new Pair<>(stat, Integer.valueOf(newval))), itemReaction, this));
    }

    public void sendPacket(Packet packet) {
        if (client != null) {
            client.sendPacket(packet);
        }
    }

    @Override
    public int getObjectId() {
        return getId();
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.PLAYER;
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(PacketCreator.removePlayerFromMap(this.getObjectId()));
    }

    @Override
    public void sendSpawnData(Client client) {
        if (!this.isHidden() || client.getPlayer().gmLevel() > 1) {
            client.sendPacket(PacketCreator.spawnPlayerMapObject(client, this, false));

            if (chair.hasMapChairBuff()) { // mustn't buffs.lock, chrLock sendSpawnData
                client.sendPacket(PacketCreator.giveForeignChairSkillEffect(id));
            }
        }

        if (this.isHidden()) {
            List<Pair<EffectType, Integer>> dsstat = Collections.singletonList(new Pair<>(EffectType.DARKSIGHT, 0));
            getMap().broadcastGMMessage(this, PacketCreator.giveForeignBuff(getId(), dsstat), false);
        }
    }

    @Override
    public void setObjectId(int id) {
    }

    @Override
    public String toString() {
        return name;
    }

    public Set<NewYearCardRecord> getNewYearRecords() {
        return newyears;
    }

    public Set<NewYearCardRecord> getReceivedNewYearRecords() {
        Set<NewYearCardRecord> received = new LinkedHashSet<>();

        for (NewYearCardRecord nyc : newyears) {
            if (nyc.isReceiverReceivedCard()) {
                received.add(nyc);
            }
        }

        return received;
    }

    public NewYearCardRecord getNewYearRecord(int cardid) {
        for (NewYearCardRecord nyc : newyears) {
            if (nyc.getId() == cardid) {
                return nyc;
            }
        }

        return null;
    }

    public void addNewYearRecord(NewYearCardRecord newyear) {
        newyears.add(newyear);
    }

    public void removeNewYearRecord(NewYearCardRecord newyear) {
        newyears.remove(newyear);
    }

    public void portalDelay(long delay) {
        this.portaldelay = System.currentTimeMillis() + delay;
    }

    public long portalDelay() {
        return portaldelay;
    }

    public void blockPortal(String scriptName) {
        if (!blockedPortals.contains(scriptName) && scriptName != null) {
            blockedPortals.add(scriptName);
            enableActions();
        }
    }

    public void unblockPortal(String scriptName) {
        if (blockedPortals.contains(scriptName) && scriptName != null) {
            blockedPortals.remove(scriptName);
        }
    }

    public boolean containsAreaInfo(int area, String info) {
        short area_ = (short) area;
        if (area_info.containsKey(area_)) {
            return area_info.get(area_).contains(info);
        }
        return false;
    }

    public void updateAreaInfo(int area, String info) {
        area_info.put((short) area, info);
        sendPacket(PacketCreator.updateAreaInfo(area, info));
    }

    public Map<Short, String> getAreaInfos() {
        return area_info;
    }

    public List<Integer> getTrockMaps() {
        return trockmaps;
    }

    public List<Integer> getVipTrockMaps() {
        return viptrockmaps;
    }

    public int getTrockSize() {
        int ret = trockmaps.indexOf(MapId.NONE);
        if (ret == -1) {
            ret = 5;
        }

        return ret;
    }

    public void deleteFromTrocks(int map) {
        trockmaps.remove(Integer.valueOf(map));
        while (trockmaps.size() < 10) {
            trockmaps.add(MapId.NONE);
        }
    }

    public void addTrockMap() {
        int index = trockmaps.indexOf(MapId.NONE);
        if (index != -1) {
            trockmaps.set(index, getMapId());
        }
    }

    public boolean isTrockMap(int id) {
        int index = trockmaps.indexOf(id);
        return index != -1;
    }

    public int getVipTrockSize() {
        int ret = viptrockmaps.indexOf(MapId.NONE);

        if (ret == -1) {
            ret = 10;
        }

        return ret;
    }

    public void deleteFromVipTrocks(int map) {
        viptrockmaps.remove(Integer.valueOf(map));
        while (viptrockmaps.size() < 10) {
            viptrockmaps.add(MapId.NONE);
        }
    }

    public void addVipTrockMap() {
        int index = viptrockmaps.indexOf(MapId.NONE);
        if (index != -1) {
            viptrockmaps.set(index, getMapId());
        }
    }

    public boolean isVipTrockMap(int id) {
        int index = viptrockmaps.indexOf(id);
        return index != -1;
    }

    public void setCpqTimer(ScheduledFuture<?> timer) {
        this.cpqSchedule = timer;
    }

    public void clearCpqTimer() {
        if (cpqSchedule != null) {
            cpqSchedule.cancel(true);
        }
        cpqSchedule = null;
    }

    public final void empty(final boolean remove) {
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(true);
        }
        dragonBloodSchedule = null;

        if (hpDecreaseTask != null) {
            hpDecreaseTask.cancel(true);
        }
        hpDecreaseTask = null;

        if (beholderHealingSchedule != null) {
            beholderHealingSchedule.cancel(true);
        }
        beholderHealingSchedule = null;

        if (beholderBuffSchedule != null) {
            beholderBuffSchedule.cancel(true);
        }
        beholderBuffSchedule = null;

        if (berserkSchedule != null) {
            berserkSchedule.cancel(true);
        }
        berserkSchedule = null;

        unregisterChairBuff();
        cancelBuffExpireTask();
        cancelDiseaseExpireTask();
        stopSkillTimers();
        cancelExpirationTask();
        quests.empty();

        if (recoveryTask != null) {
            recoveryTask.cancel(true);
        }
        recoveryTask = null;

        if (extraRecoveryTask != null) {
            extraRecoveryTask.cancel(true);
        }
        extraRecoveryTask = null;

        inventory.clearPendantOfSpirit();

        clearCpqTimer();

        if (mapleMount != null) {
            mapleMount.empty();
            mapleMount = null;
        }
        if (remove) {
            pq.setPartyQuest(null);
            events = null;
            party.mpc = null;
            guild.setMGC(null);
            party.party = null;
            FamilyEntry familyEntry = family.getFamilyEntry();
            if (familyEntry != null) {
                familyEntry.setCharacter(null);
                family.setFamilyEntry(null);
            }

            getWorldServer().registerTimedMapObject(() -> {
                client = null;  // clients still triggers handlers a few times after disconnecting
        setMap((MapleMap) null);

                // thanks Shavit for noticing a memory leak with inventories holding owner object
                inventory.disposeAll();
            }, MINUTES.toMillis(5));
        }
    }

    public void logOff() {
        this.loggedIn = false;
        characterService.update(CharactersDO.builder()
                .id(id)
                .lastLogoutTime(new Timestamp(System.currentTimeMillis()))
                .build());
    }

    public long getLoggedInTime() {
        return System.currentTimeMillis() - loginTime;
    }

    public void createDragon() {
        dragon = new Dragon(this);
    }

    public boolean registerNameChange(String newName) {
        try {
            if (nameChangeService.registerNameChange(this, newName)) {
                pendingNameChange = true;
                return true;
            }
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Character.registerNameChange.error1"), getName(), newName, e);
        }
        return false;
    }

    public boolean cancelPendingNameChange() {
        try {
            nameChangeService.cancelPendingNameChange(this, true);
            return true;
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Character.cancelPendingNameChange.error1"), getName(), e);
            return false;
        }
    }

    public void doPendingNameChange() { //called on logout
        if (!pendingNameChange) {
            return;
        }
        nameChangeService.applyNameChange(getId(), getName());
    }

    public int checkWorldTransferEligibility() {
        if (getLevel() < 20) {
            return 2;
        } else if (getClient().getTempBanCalendar() != null && getClient().getTempBanCalendar().getTimeInMillis() + (int) DAYS.toMillis(30) < Calendar.getInstance().getTimeInMillis()) {
            return 3;
        } else if (isMarried()) {
            return 4;
        } else if (getGuildRank() < 2) {
            return 5;
        } else if (getFamily() != null) {
            return 8;
        } else {
            return 0;
        }
    }

    public boolean registerWorldTransfer(int newWorld) {
        try {
            return worldTransferService.registerWorldTransfer(this, newWorld);
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Character.registerWorldTransfer.error1"), getName(), newWorld, e);
        }
        return false;
    }

    public boolean cancelPendingWorldTransfer() {
        try {
            worldTransferService.cancelPendingWorldTransfer(this, true);
            return true;
        } catch (Exception e) {
            log.error(I18nUtil.getLogMessage("Character.cancelPendingWorldTransfer.error1"), getName(), e);
            return false;
        }
    }

    public String getLastCommandMessage() {
        return this.commandtext;
    }

    public void setLastCommandMessage(String text) {
        this.commandtext = text;
    }

    public int getRewardPoints() {
        AccountsDO accountsDO = accountService.findById(accountId);
        return accountsDO == null ? -1 : Optional.ofNullable(accountsDO.getRewardpoints()).orElse(-1);
    }

    public void setRewardPoints(int value) {
        accountService.update(AccountsDO.builder()
                .id(accountId)
                .rewardpoints(value)
                .build());
    }

    //EVENTS
    @Setter
    @Getter
    private Fitness fitness;
    @Setter
    @Getter
    private Ola ola;
    private long snowballattack;

    public long getLastSnowballAttack() {
        return snowballattack;
    }

    public void setLastSnowballAttack(long time) {
        this.snowballattack = time;
    }

    // MCPQ 相关字段与方法见 CharacterPartyQuest 组件

    /////////////////////////////////////////////////////////////////////////////////
    //module: 角色在线时间
    private int m_iCurrentOnlineTime = -1;//-1用于服务器重启时角色初始变量时间

    public int getCurrentOnlineTime() {
        return this.m_iCurrentOnlineTime;
    }

    public void setCurrentOnlineTime(final int iTime) {
        this.m_iCurrentOnlineTime = iTime;
    }

    public void updateOnlineTime() {
        if (m_iCurrentOnlineTime == -1) {
            return;
        }
        String strNewOnlineTime = String.valueOf(m_iCurrentOnlineTime);
        getAbstractPlayerInteraction().saveOrUpdateAccountExtendValue(ExtendKey.ONLINE_TIME.getKey(), strNewOnlineTime, true);
    }

    /**
     * 通知客户端启用操作，解除假死
     */
    public void enableActions() {
        sendPacket(PacketCreator.enableActions());
    }

    // ==================== 攻击间隔滑动窗口（稳定度识别） ====================

    // ══════════════════ 组件门面（按模块归类） ══════════════════

    // ── buffs 门面 ──

    public Long getBuffedStarttime(EffectType effect) { return buffs.getActive().getBuffedStarttime(effect); }
    public Integer getBuffedValue(EffectType effect) { return buffs.getActive().getBuffedValue(effect); }
    public int getBuffSource(EffectType stat) { return buffs.getActive().getBuffSource(stat); }
    public BuffEffectData getBuffEffect(EffectType stat) { return buffs.getActive().getBuffEffect(stat); }
    public void updateActiveEffects() { buffs.updateActiveEffects(); }
    public void registerEffect(BuffEffectData effect, long starttime, long expirationtime) { buffs.registerEffect(effect, starttime, expirationtime); }
    public boolean cancelEffect(BuffEffectData effect, boolean overwrite) { return buffs.cancelBuff(effect.getBuffSourceId(), overwrite); }
    public void cancelEffectFromBuffStat(EffectType stat) { buffs.cancelEffectFromBuffStat(stat); }
    public void cancelBuffStats(EffectType stat) { buffs.cancelBuffStats(stat); }
    public boolean isBuffsFrozen() { return buffs.isFrozen(); }
    public boolean isBuffFrom(EffectType stat, Skill skill) { return buffs.getActive().isBuffFrom(stat, skill); }
    public void setBuffedValue(EffectType effect, int value) { buffs.getActive().setBuffedValue(effect, value); }
    public List<BuffEffectData> getAllBuffs() { return buffs.getAllBuffEffectData(); }
    public boolean hasBuffFromSourceid(int sourceid) { return buffs.containsSourceId(sourceid); }
    public boolean hasActiveBuff(int sourceid) { return buffs.getActive().hasActiveBuff(sourceid); }
    public void debugListAllBuffs() { buffs.debugListAllBuffs(); }
    public void cancelAllBuffs(boolean softcancel) { buffs.cancelAllBuffs(softcancel); }
    public void buffExpireTask() { buffs.startExpireTimer(); }
    public void cancelBuffExpireTask() { buffs.stopExpireTimer(); }
    public void dispel() { buffs.dispel(); }
    public void dispelSkill(int skillid) { buffs.dispelSkill(skillid); }
    public BuffEffectData getStatForBuff(EffectType effect) { return buffs.getStatForBuff(effect); }

    // ── pets 门面 ──

    public void addPet(Pet pet) { pets.addPet(pet); }
    public void resetExcluded(int petId) { pets.resetExcluded(petId); }
    public void addExcluded(int petId, int x) { pets.addExcluded(petId, x); }
    public void loadPetExcludedItems(int petId) { pets.loadPetExcludedItems(petId); }
    public void updatePetExcludedItems(int petId, Set<Integer> newExcludedItems) { pets.updatePetExcludedItems(petId, newExcludedItems); }
    public void deletePetExcludedData(int petId) { pets.deletePetExcludedData(petId); }
    public Set<Integer> getExcludedForPet(int petId) { return pets.getExcludedForPet(petId); }
    public void commitExcludedItems() { pets.commitExcludedItems(); }
    public void exportExcludedItems(Client c) { pets.exportExcludedItems(c); }
    public Map<Integer, Set<Integer>> getExcluded() { return pets.getExcluded(); }
    public Set<Integer> getExcludedItems() { return pets.getExcludedItems(); }
    public int getNoPets() { return pets.getNoPets(); }
    public Pet[] getPets() { return pets.getPets(); }
    public Pet getPet(int index) { return pets.getPet(index); }
    public byte getPetIndex(int petId) { return pets.getPetIndex(petId); }
    public byte getPetIndex(Pet pet) { return pets.getPetIndex(pet); }
    public int getPetEquipItemId(byte petIndex) { return pets.getPetEquipItemId(petIndex); }
    public boolean hasPetNameTag(byte petIndex) { return pets.hasPetNameTag(petIndex); }
    public boolean hasPetChatballoon(byte petIndex) { return pets.hasPetChatballoon(petIndex); }
    public boolean isEquippedMesoMagnet(byte petIndex) { return pets.isEquippedMesoMagnet(petIndex); }
    public boolean isEquippedItemPouch(byte petIndex) { return pets.isEquippedItemPouch(petIndex); }
    public boolean isEquippedPetItemIgnore(byte petIndex) { return pets.isEquippedPetItemIgnore(petIndex); }
    public void removePet(Pet pet, boolean shift_left) { pets.removePet(pet, shift_left); }
    public void shiftPetsRight() { pets.shiftPetsRight(); }
    public void runFullnessSchedule(int petSlot) { pets.runFullnessSchedule(petSlot); }
    public void unEquipAllPets() { pets.unEquipAllPets(); }
    public void unEquipPet(Pet pet, boolean shift_left) { pets.unEquipPet(pet, shift_left); }
    public void unEquipPet(Pet pet, boolean shift_left, boolean hunger) { pets.unEquipPet(pet, shift_left, hunger); }
    public void setPetLootTeleportBeforePos(Point pos) { pets.setPetLootTeleportBeforePos(pos); }
    public Point getPetLootTeleportBeforePos() { return pets.getPetLootTeleportBeforePos(); }

    // ── debuffs 门面 ──

    public final boolean hasDisease(final Disease dis) { return debuffs.hasDebuff(dis); }
    public final int getDiseasesSize() { return debuffs.getDebuffsSize(); }
    public void announceDiseases() { debuffs.announceDebuffs(); }
    public void announceDebuffsToOwner() { debuffs.announceDebuffsToOwner(); }
    public void collectDiseases() { debuffs.collectDebuffs(); }
    public void giveDebuff(final Disease disease, MobSkill skill) { debuffs.giveDebuff(disease, skill); }
    public void dispelDebuff(Disease debuff) { debuffs.dispelDebuff(debuff); }
    public void dispelDebuffs() { debuffs.dispelDebuffs(); }
    public void purgeDebuffs() { debuffs.purgeDebuffs(); }
    public void cancelAllDebuffs() { debuffs.cancelAllDebuffs(); }
    public void cancelDiseaseExpireTask() { debuffs.stopExpireTimer(); }
    public void diseaseExpireTask() { debuffs.startExpireTimer(); }

    // ── chair 门面 ──

    public boolean unregisterChairBuff() { return chair.unregisterChairBuff(); }
    public boolean registerChairBuff() { return chair.registerChairBuff(); }
    public int getChair() { return chair.getChair(); }
    public void sitChair(int itemId) { chair.sitChair(itemId); }

    // ── job 门面 ──

    public JobEnum getJobStyle(byte opt) { return job.getJobStyle(opt); }
    public JobEnum getJobStyle() { return job.getJobStyle(); }
    public synchronized void changeJob(JobEnum newJob) { job.changeJob(newJob); }
    public JobEnum getJob() { return job.getJob(); }
    public void setJob(JobEnum newJob) { job.setJob(newJob); }
    public int getJobType() { return job.getJobType(); }
    public boolean isGmJob() { return job.isGmJob(); }
    public boolean isCygnus() { return job.isCygnus(); }
    public boolean isAran() { return job.isAran(); }
    public boolean isBeginnerJob() { return job.isBeginnerJob(); }

    // ── map 门面 ──

    public MapleMap getWarpMap(int mapid) { return map.getWarpMap(mapid); }
    public void warpAhead(int mapid) { map.warpAhead(mapid); }
    public void changeMap(int mapid) { map.changeMap(mapid); }
    public void changeMap(int mapid, Object pt) { map.changeMap(mapid, pt); }
    public void changeMap(MapleMap to) { map.changeMap(to); }
    public void changeMap(MapleMap to, int portal) { map.changeMap(to, portal); }
    public void changeMap(final MapleMap target, Portal pto) { map.changeMap(target, pto); }
    public void changeMap(final MapleMap target, final Point pos) { map.changeMap(target, pos); }
    public void forceChangeMap(final MapleMap target, Portal pto) { map.forceChangeMap(target, pto); }
    public List<Integer> getLastVisitedMapIds() { return map.getLastVisitedMapIds(); }
    public void visitMap(MapleMap to) { map.visitMap(to); }
    public boolean isChangingMaps() { return map.isChangingMaps(); }
    public void setMapTransitionComplete() { map.setMapTransitionComplete(); }
    public MapleMap getMap() { return map.getMap(); }
    public int getMapId() { return map.getMapId(); }
    public void setMap(MapleMap to) { map.setMap(to); }
    public void setMap(int PmapId) { map.setMap(PmapId); }
    public void setMapId(int mapId) { map.setMapId(mapId); }
    public MapleMap getMap(int mapid, boolean showMsg) { return map.getMap(mapid, showMsg); }
    public boolean canRecoverLastBanish() { return map.canRecoverLastBanish(); }
    public Pair<Integer, Integer> getLastBanishData() { return map.getLastBanishData(); }
    public void clearBanishPlayerData() { map.clearBanishPlayerData(); }
    public void setBanishPlayerData(int banishMap, int banishSp, long banishTime) { map.setBanishPlayerData(banishMap, banishSp, banishTime); }
    public void changeMapBanish(int mapid, String portal, String msg) { map.changeMapBanish(mapid, portal, msg); }

    // ── ap 门面 ──

    public int getRemainingAp() { return ap.getRemainingAp(); }
    public int getHpMpApUsed() { return ap.getHpMpApUsed(); }
    public boolean assignHP(int deltaHP, int deltaAp) { return ap.assignHP(deltaHP, deltaAp); }
    public boolean assignMP(int deltaMP, int deltaAp) { return ap.assignMP(deltaMP, deltaAp); }
    public boolean assignStr(int x) { return ap.assignAttr(STR, x); }
    public boolean assignDex(int x) { return ap.assignAttr(DEX, x); }
    public boolean assignInt(int x) { return ap.assignAttr(INT, x); }
    public boolean assignLuk(int x) { return ap.assignAttr(LUK, x); }
    public void changeRemainingAp(int x, boolean silent) { ap.changeRemainingAp(x, silent); }
    public void gainAp(int deltaAp, boolean silent) { ap.gainAp(deltaAp, silent); }

    // ── sp 门面 ──

    public int getRemainingSp(int jobId) { return sp.getRemainingSp(jobId); }
    public int[] getRemainingSps() { return sp.getRemainingSps(); }
    public void setRemainingSp(int remainingSp, int jobId) { sp.setRemainingSp(remainingSp, jobId); }
    public void gainSp(int deltaSp, int jobId, boolean silent) { sp.gainSp(deltaSp, jobId, silent); }

    // ── stats 门面 ──

    public int getStr() { return stats.getBase(STR); }
    public int getDex() { return stats.getBase(DEX); }
    public int getInt() { return stats.getBase(INT); }
    public int getLuk() { return stats.getBase(LUK); }
    public void healHpMp() { stats.update().setHp(30000).setMp(30000).commit(); }
    public void updateHpMp(int x) { stats.update().setHp(x).setMp(x).commit(); }
    public void updateHpMp(int newhp, int newmp) { stats.update().setHp(newhp).setMp(newmp).commit(); }
    public void updateHp(int hp) { stats.update().setHp(hp).commit(); }
    public void updateMaxHp(int maxhp) { stats.update().set(MAX_HP, maxhp).commit(); }
    public void updateHpMaxHp(int hp, int maxhp) { stats.update().setHp(hp).set(MAX_HP, maxhp).commit(); }
    public void updateMp(int mp) { stats.update().setMp(mp).commit(); }
    public void updateMaxMp(int maxmp) { stats.update().set(MAX_MP, maxmp).commit(); }
    public void updateMpMaxMp(int mp, int maxmp) { stats.update().setMp(mp).set(MAX_MP, maxmp).commit(); }
    public void updateMaxHpMaxMp(int maxhp, int maxmp) { stats.update().set(MAX_HP, maxhp).set(MAX_MP, maxmp).commit(); }
    public int safeAddHP(int delta) { return stats.safeAddHP(delta); }
    public void addHP(int delta) { stats.update().addHp(delta).commit(); }
    public void addMP(int delta) { stats.update().addMp(delta).commit(); }
    public void addMPHP(int hpDelta, int mpDelta) { stats.update().addHp(hpDelta).addMp(mpDelta).commit(); }
    public void addMaxHP(int delta) { stats.update().add(Stat.MAX_HP, delta).commit(); }
    public void addMaxMP(int delta) { stats.update().add(Stat.MAX_MP, delta).commit(); }
    public void recalc() { stats.recalc(); }
    public boolean applyHpMpChange(int hpCon, int hpchange, int mpchange) { return stats.applyHpMpChange(hpCon, hpchange, mpchange); }

    public void changeHpMp(int newhp, int newmp, boolean silent) {
        if (silent) {
            stats.update().setHp(newhp).setMp(newmp).commitSilently();
        } else {
            stats.update().setHp(newhp).setMp(newmp).commit();
        }
    }

    // ── rates 门面 ──

    public float getMesoRate() { return rates.getMesoRate(); }
    public float getDropRate() { return rates.getDropRate(); }
    public boolean hasNoviceExpRate() { return rates.hasNoviceExpRate(); }
    public float getExpRate() { return rates.getExpRate(); }
    public float getLevelExpRate() { return rates.getLevelExpRate(); }
    public float getQuickLevelExpRate() { return rates.getQuickLevelExpRate(); }
    public void updateMobExpRate() { rates.updateMobExpRate(); }
    public float getMobExpRate() { return rates.getMobExpRate(); }
    public int getCouponExpRate() { return rates.getCouponExpRate(); }
    public float getRawExpRate() { return rates.getRawExpRate(); }
    public int getCouponDropRate() { return rates.getCouponDropRate(); }
    public float getRawDropRate() { return rates.getRawDropRate(); }
    public float getBossDropRate() { return rates.getBossDropRate(); }
    public int getCouponMesoRate() { return rates.getCouponMesoRate(); }
    public float getRawMesoRate() { return rates.getRawMesoRate(); }
    public float getQuestExpRate() { return rates.getQuestExpRate(); }
    public float getQuestMesoRate() { return rates.getQuestMesoRate(); }
    public float getCardRate(int itemid) { return rates.getCardRate(itemid); }
    public void setPlayerRates() { rates.setPlayerRates(); }
    public void revertLastPlayerRates() { rates.revertLastPlayerRates(); }
    public void revertPlayerRates() { rates.revertPlayerRates(); }
    public void setWorldRates() { rates.setWorldRates(); }
    public void revertWorldRates() { rates.revertWorldRates(); }
    public void setCouponRates() { rates.setCouponRates(); }
    public void updateCouponRates() { rates.updateCouponRates(); }
    public void resetPlayerRates() { rates.resetPlayerRates(); }
    public void dispelBuffCoupons() { rates.dispelBuffCoupons(); }
    public Set<Integer> getActiveCoupons() { return rates.getActiveCoupons(); }

    // ── skills 门面 ──

    public void addCooldown(int skillId, long startTime, long length) { skills.addCooldown(skillId, startTime, length); }
    public void changeSkillLevel(Skill skill, int newLevel, int newMasterlevel, long expiration) { skills.changeSkillLevel(skill, newLevel, newMasterlevel, expiration); }
    public void stopSkillTimers() { skills.stopTimers(); }
    public void startSkillTimers() { skills.startTimers(); }
    public List<PlayerCoolDownValueHolder> getAllCooldowns() { return skills.getAllCooldowns(); }
    public int getMasterLevel(int skill) { return skills.getMasterLevel(skill); }
    public int getMasterLevel(Skill skill) { return skills.getMasterLevel(skill); }
    public Map<Skill, SkillEntry> getSkills() { return skills.getSkillsView(); }
    public int getSkillLevel(int skill) { return skills.getSkillLevel(skill); }
    public int getSkillLevel(Skill skill) { return skills.getSkillLevel(skill); }
    public long getSkillExpiration(int skill) { return skills.getSkillExpiration(skill); }
    public long getSkillExpiration(Skill skill) { return skills.getSkillExpiration(skill); }
    public void removeAllCooldownsExcept(int id, boolean packet) { skills.removeAllCooldownsExcept(id, packet); }
    public void removeCooldown(int skillId) { skills.removeCooldown(skillId); }
    public boolean skillIsCooling(int skillId) { return skills.skillIsCooling(skillId); }

    // ── antiCheat 门面 ──

    /** 滑动窗口判定结果（public API：供 AbstractDealDamageHandler 等外部 switch，判定逻辑在 CharacterAntiCheat） */
    public enum SkillWindowResult {
        PASS,          // 数据不足 / avg >= 250 → 正常
        STABLE_HACK,   // avg < 250 且 CV < STABLE_CV → 稳定高速
        BURST          // avg < 250 但 CV >= STABLE_CV → 网络暴发
    }

    /** 网络抖动透明上限：< 此值的间隔不更新状态、不入窗口（转发 CharacterAntiCheat） */
    public static final long MIN_INTERVAL = CharacterAntiCheat.MIN_INTERVAL;
    /** 平均阈值：窗口 avg >= 此值判定为正常频率（转发 CharacterAntiCheat） */
    public static final long NORMAL_AVG = CharacterAntiCheat.NORMAL_AVG;

    public void ban(String reason) { antiCheat.ban(reason); }
    public static boolean ban(String id, String reason, boolean accountId) { return CharacterAntiCheat.ban(id, reason, accountId); }
    public void autoBan(String reason) { antiCheat.autoBan(reason); }
    public void block(int reason, int days, String desc) { antiCheat.block(reason, days, desc); }
    public void sendPolice(int greason, String reason, int duration) { antiCheat.sendPolice(greason, reason, duration); }
    public void sendPolice(String text) { antiCheat.sendPolice(text); }
    public boolean isBanned() { return antiCheat.isBanned(); }
    public void setBanned(boolean banned) { antiCheat.setBanned(banned); }
    public AutobanManager getAutoBanManager() { return antiCheat.getAutoBanManager(); }
    public void setAutoBanManager(AutobanManager autoBan) { antiCheat.setAutoBanManager(autoBan); }
    public long getJailExpirationTimeLeft() { return antiCheat.getJailExpirationTimeLeft(); }
    public void addJailExpirationTime(long time) { antiCheat.addJailExpirationTime(time); }
    public void removeJailExpirationTime() { antiCheat.removeJailExpirationTime(); }
    public SkillWindowResult checkSkillWindow(int skillId, long interval) { return antiCheat.checkSkillWindow(skillId, interval); }
    public CharacterAntiCheat.AttackWindow getSkillWindow(int skillId) { return antiCheat.getSkillWindow(skillId); }
    public long getGlobalInterval(long now) { return antiCheat.getGlobalInterval(now); }
    public void updateGlobalTime(long now) { antiCheat.updateGlobalTime(now); }
    public synchronized void markTeleportLikeMove(Point beforePos, Point afterPos) { antiCheat.markTeleportLikeMove(beforePos, afterPos); }
    public synchronized void markRegularMove(Point beforePos, Point afterPos) { antiCheat.markRegularMove(beforePos, afterPos); }
    public synchronized Point getTeleportBeforePositionForDistanceCheck() { return antiCheat.getTeleportBeforePositionForDistanceCheck(); }
    public synchronized Point getMovementBeforePositionForDistanceCheck() { return antiCheat.getMovementBeforePositionForDistanceCheck(); }
    public synchronized void consumeTeleportDistanceCheckContext() { antiCheat.consumeTeleportDistanceCheckContext(); }
    public synchronized void consumeMovementDistanceCheckContext() { antiCheat.consumeMovementDistanceCheckContext(); }
    public synchronized void clearTeleportDistanceContext() { antiCheat.clearTeleportDistanceContext(); }
    public long getAttackInterval(int skillId, long now) { return antiCheat.getAttackInterval(skillId, now); }

    // ── market 门面 ──

    public HiredMerchant getHiredMerchant() { return market.getHiredMerchant(); }
    public void setHiredMerchant(HiredMerchant hiredMerchant) { market.setHiredMerchant(hiredMerchant); }
    public PlayerShop getPlayerShop() { return market.getPlayerShop(); }
    public void setPlayerShop(PlayerShop playerShop) { market.setPlayerShop(playerShop); }
    public boolean hasMerchant() { return market.hasMerchant(); }
    public int getMerchantMeso() { return market.getMerchantMeso(); }
    public int getMerchantNetMeso() { return market.getMerchantNetMeso(); }
    public void setHasMerchant(boolean set) { market.setHasMerchant(set); }
    public void addMerchantMesos(int add) { market.addMerchantMesos(add); }
    public void setMerchantMeso(int set) { market.setMerchantMeso(set); }
    public synchronized void withdrawMerchantMesos() { market.withdrawMerchantMesos(); }
    public void closePlayerShop() { market.closePlayerShop(); }
    public void closeHiredMerchant(boolean closeMerchant) { market.closeHiredMerchant(closeMerchant); }

    // ── quest 门面 ──

    public Map<Short, QuestStatus> getQuests() { return quests.getQuests(); }
    public QuestStatus getQuest(final int quest) { return quests.getQuest(quest); }
    public QuestStatus getQuest(Quest quest) { return quests.getQuest(quest); }
    public byte getQuestStatus(final int quest) { return quests.getQuestStatus(quest); }
    public QuestStatus getQuestNoAdd(final Quest quest) { return quests.getQuestNoAdd(quest); }
    public QuestStatus getQuestNAdd(final Quest quest) { return quests.getQuestNAdd(quest); }
    public List<QuestStatus> getCompletedQuests() { return quests.getCompletedQuests(); }
    public List<QuestStatus> getStartedQuests() { return quests.getStartedQuests(); }
    public boolean needQuestItem(int questid, int itemid) { return quests.needQuestItem(questid, itemid); }
    public void updateQuestStatus(QuestStatus qs) { quests.updateQuestStatus(qs); }
    public void setQuestProgress(int id, int infoNumber, String progress) { quests.setQuestProgress(id, infoNumber, progress); }
    public void announceUpdateQuest(DelayedQuestUpdate questUpdateType, Object... params) { quests.announceUpdateQuest(questUpdateType, params); }
    public void flushDelayedUpdateQuests() { quests.flushDelayedUpdateQuests(); }
    public void questTimeLimit(final Quest quest, int seconds) { quests.questTimeLimit(quest, seconds); }
    public void questTimeLimit2(final Quest quest, long expires) { quests.questTimeLimit2(quest, expires); }
    public void raiseQuestMobCount(int id) { quests.raiseQuestMobCount(id); }
    public void forfeitExpirableQuests() { quests.forfeitExpirableQuests(); }
    public void questExpirationTask() { quests.questExpirationTask(); }
    public void cancelQuestExpirationTask() { quests.cancelQuestExpirationTask(); }
    public void reloadQuestExpirations() { quests.reloadQuestExpirations(); }
    public void awardQuestPoint(int awardedPoints) { quests.awardQuestPoint(awardedPoints); }

    // ── party 门面 ──

    public Party getParty() { return party.getParty(); }
    public int getPartyId() { return party.getPartyId(); }
    public List<Character> getPartyMembersOnline() { return party.getPartyMembersOnline(); }
    public List<Character> getPartyMembersOnSameMap() { return party.getPartyMembersOnSameMap(); }
    public boolean isPartyMember(Character chr) { return party.isPartyMember(chr); }
    public boolean isPartyMember(int cid) { return party.isPartyMember(cid); }
    public boolean isPartyLeader() { return party.isPartyLeader(); }
    public PartyCharacter getMPC() { return party.getMPC(); }
    public void setMPC(PartyCharacter mpc) { party.setMPC(mpc); }
    public void setParty(Party p) { party.setParty(p); }
    public boolean leaveParty() { return party.leaveParty(); }
    public void silentPartyUpdate() { party.silentPartyUpdate(); }
    public void updatePartyMemberHP() { party.updatePartyMemberHP(); }
    public void partyOperationUpdate(Party party, List<Character> exPartyMembers) { this.party.partyOperationUpdate(party, exPartyMembers); }
    public void updatePartySearchAvailability(boolean pSearchAvailable) { party.updatePartySearchAvailability(pSearchAvailable); }
    public boolean toggleRecvPartySearchInvite() { return party.toggleRecvPartySearchInvite(); }
    public boolean isRecvPartySearchInviteEnabled() { return party.isRecvPartySearchInviteEnabled(); }
    public void receivePartyMemberHP() { party.receivePartyMemberHP(); }

    // ── door 门面 ──

    public boolean canDoor() { return door.canDoor(); }
    public Collection<Door> getDoors() { return door.getDoors(); }
    public Door getPlayerDoor() { return door.getPlayerDoor(); }
    public Door getMainTownDoor() { return door.getMainTownDoor(); }
    public void applyPartyDoor(Door door, boolean partyUpdate) { this.door.applyPartyDoor(door, partyUpdate); }
    public Door removePartyDoor(boolean partyUpdate) { return door.removePartyDoor(partyUpdate); }
    public int getDoorSlot() { return door.getDoorSlot(); }
    public int fetchDoorSlot() { return door.fetchDoorSlot(); }
    public void cancelMagicDoor() { door.cancelMagicDoor(); }

    // ── pq 门面 ──

    public PartyQuest getPartyQuest() { return pq.getPartyQuest(); }
    public void setPartyQuest(PartyQuest partyQuest) { pq.setPartyQuest(partyQuest); }
    public AriantColiseum getAriantColiseum() { return pq.getAriantColiseum(); }
    public void setAriantColiseum(AriantColiseum ariantColiseum) { pq.setAriantColiseum(ariantColiseum); }
    public MonsterCarnival getMonsterCarnival() { return pq.getMonsterCarnival(); }
    public void setMonsterCarnival(MonsterCarnival monsterCarnival) { pq.setMonsterCarnival(monsterCarnival); }
    public MonsterCarnivalParty getMonsterCarnivalParty() { return pq.getMonsterCarnivalParty(); }
    public void setMonsterCarnivalParty(MonsterCarnivalParty monsterCarnivalParty) { pq.setMonsterCarnivalParty(monsterCarnivalParty); }
    public byte getTeam() { return pq.getTeam(); }
    public void setTeam(int team) { pq.setTeam(team); }
    public int getCP() { return pq.getCP(); }
    public void setCP(int a) { pq.setCP(a); }
    public int getTotalCP() { return pq.getTotalCP(); }
    public void setTotalCP(int a) { pq.setTotalCP(a); }
    public void gainCP(int gain) { pq.gainCP(gain); }
    public void resetCP() { pq.resetCP(); }
    public void gainAriantPoints(int points) { pq.gainAriantPoints(points); }
    public void gainFestivalPoints(int gain) { pq.gainFestivalPoints(gain); }
    public int getFestivalPoints() { return pq.getFestivalPoints(); }
    public void setFestivalPoints(int FestivalPoints) { pq.setFestivalPoints(FestivalPoints); }
    public boolean isChallenged() { return pq.isChallenged(); }
    public void setChallenged(boolean challenged) { pq.setChallenged(challenged); }
    public void updateAriantScore() { pq.updateAriantScore(); }
    public void updateAriantScore(int dropQty) { pq.updateAriantScore(dropQty); }
    public boolean gotPartyQuestItem(String partyquestchar) { return pq.gotPartyQuestItem(partyquestchar); }
    public void removePartyQuestItem(String letter) { pq.removePartyQuestItem(letter); }
    public void setPartyQuestItemObtained(String partyquestchar) { pq.setPartyQuestItemObtained(partyquestchar); }
    public int getAriantPoints() { return pq.getAriantPoints(); }
    public void setAriantPoints(int ariantPoints) { pq.setAriantPoints(ariantPoints); }
    public String getDataString() { return pq.getDataString(); }
    public void setDataString(String dataString) { pq.setDataString(dataString); }

    // ── guild 门面 ──

    public int getGuildId() { return guild.getGuildId(); }
    public void setGuildId(int guildId) { guild.setGuildId(guildId); }
    public int getGuildRank() { return guild.getGuildRank(); }
    public void setGuildRank(int guildRank) { guild.setGuildRank(guildRank); }
    public int getAllianceRank() { return guild.getAllianceRank(); }
    public void setAllianceRank(int allianceRank) { guild.setAllianceRank(allianceRank); }
    public GuildCharacter getMGC() { return guild.getMGC(); }
    public void setMGC(GuildCharacter mgc) { guild.setMGC(mgc); }
    public Guild getGuild() { return guild.getGuild(); }
    public Alliance getAlliance() { return guild.getAlliance(); }
    public boolean isGuildLeader() { return guild.isGuildLeader(); }
    public void deleteGuild(int guildId) { guild.deleteGuild(guildId); }
    public void disbandGuild() { guild.disbandGuild(); }
    public void genericGuildMessage(int code) { guild.genericGuildMessage(code); }
    public void increaseGuildCapacity() { guild.increaseGuildCapacity(); }
    public void saveGuildStatus() { guild.saveGuildStatus(); }

    // ── inventory 门面 ──

    public Inventory getInventory(InventoryType type) { return inventory.getInventory(type); }
    public int countItem(int itemid) { return inventory.countItem(itemid); }
    public boolean canHold(int itemid) { return inventory.canHold(itemid); }
    public boolean canHold(int itemid, int quantity) { return inventory.canHold(itemid, quantity); }
    public boolean canHoldUniques(List<Integer> itemids) { return inventory.canHoldUniques(itemids); }
    public boolean canHoldMeso(int gain) { return inventory.canHoldMeso(gain); }
    public boolean haveItemWithId(int itemid, boolean checkEquipped) { return inventory.haveItemWithId(itemid, checkEquipped); }
    public boolean haveItemEquipped(int itemid) { return inventory.haveItemEquipped(itemid); }
    public boolean haveWeddingRing() { return inventory.haveWeddingRing(); }
    public int getItemQuantity(int itemid, boolean checkEquipped) { return inventory.getItemQuantity(itemid, checkEquipped); }
    public int getCleanItemQuantity(int itemid, boolean checkEquipped) { return inventory.getCleanItemQuantity(itemid, checkEquipped); }
    public boolean haveItem(int itemid) { return inventory.haveItem(itemid); }
    public boolean haveCleanItem(int itemid) { return inventory.haveCleanItem(itemid); }
    public boolean hasEmptySlot(int itemId) { return inventory.hasEmptySlot(itemId); }
    public boolean hasEmptySlot(byte invType) { return inventory.hasEmptySlot(invType); }
    public byte getSlots(int type) { return inventory.getSlots(type); }
    public boolean canGainSlots(int type, int slots) { return inventory.canGainSlots(type, slots); }
    public boolean gainSlots(int type, int slots) { return inventory.gainSlots(type, slots); }
    public boolean gainSlots(int type, int slots, boolean update) { return inventory.gainSlots(type, slots, update); }
    public int getSlot() { return inventory.getSlot(); }
    public void setSlot(int slotid) { inventory.setSlot(slotid); }
    public void setCS(boolean cs) { inventory.setCS(cs); }
    public boolean isUseCS() { return inventory.isUseCS(); }
    public void equipChanged() { inventory.equipChanged(); }
    public void cancelExpirationTask() { inventory.cancelExpirationTask(); }
    public void expirationTask() { inventory.expirationTask(); }
    public void forceUpdateItem(Item item) { inventory.forceUpdateItem(item); }
    public void setHasSandboxItem() { inventory.setHasSandboxItem(); }
    public void removeSandboxItems() { inventory.removeSandboxItems(); }
    public int sellAllItemsFromName(byte invTypeId, String name) { return inventory.sellAllItemsFromName(invTypeId, name); }
    public int sellAllItemsFromPosition(ItemInformationProvider ii, InventoryType type, short pos) { return inventory.sellAllItemsFromPosition(ii, type, pos); }
    public final void pickupItem(MapObject ob) { inventory.pickupItem(ob); }
    public final void pickupItem(MapObject ob, int petIndex) { inventory.pickupItem(ob, petIndex); }
    public boolean mergeAllItemsFromName(String name) { return inventory.mergeAllItemsFromName(name); }
    public void mergeAllItemsFromPosition(Map<StatUpgrade, Float> statUps, short pos) { inventory.mergeAllItemsFromPosition(statUps, pos); }
    public void increaseEquipExp(int expGain) { inventory.increaseEquipExp(expGain); }
    public void showAllEquipFeatures() { inventory.showAllEquipFeatures(); }
    public void gainEquip(int itemId, Short attStr, Short attDex, Short attInt, Short attLuk, Short attHp, Short attMp, Short pAtk, Short mAtk, Short pDef, Short mDef, Short acc, Short avoid, Short hands, Short speed, Short jump, Byte upgradeSlot, Long expireTime) { inventory.gainEquip(itemId, attStr, attDex, attInt, attLuk, attHp, attMp, pAtk, mAtk, pDef, mDef, acc, avoid, hands, speed, jump, upgradeSlot, expireTime); }
    public void equippedItem(Equip equip) { inventory.equippedItem(equip); }
    public void unequippedItem(Equip equip) { inventory.unequippedItem(equip); }

    // ── family 门面 ──

    public Family getFamily() { return family.getFamily(); }
    public FamilyEntry getFamilyEntry() { return family.getFamilyEntry(); }
    public void setFamilyEntry(FamilyEntry entry) { family.setFamilyEntry(entry); }
    public int getFamilyId() { return family.getFamilyId(); }
    public void setFamilyId(int familyId) { family.setFamilyId(familyId); }
    public boolean isFamilyBuff() { return family.isFamilyBuff(); }
    public void setFamilyBuff(boolean type, float exp, float drop) { family.setFamilyBuff(type, exp, drop); }
    public float getFamilyExp() { return family.getFamilyExp(); }
    public float getFamilyDrop() { return family.getFamilyDrop(); }
    public void startFamilyBuffTimer(int delay) { family.startFamilyBuffTimer(delay); }
    public void cancelFamilyBuffTimer() { family.cancelFamilyBuffTimer(); }

    // ── marriage 门面 ──

    public Ring getMarriageRing() { return marriage.getMarriageRing(); }
    public void setMarriageRing(Ring marriageRing) { marriage.setMarriageRing(marriageRing); }
    public Ring getRingById(int id) { return marriage.getRingById(id); }
    public int getRelationshipId() { return marriage.getRelationshipId(); }
    public boolean isMarried() { return marriage.isMarried(); }
    public boolean hasJustMarried() { return marriage.hasJustMarried(); }
    public List<Ring> getCrushRings() { return marriage.getCrushRings(); }
    public List<Ring> getFriendshipRings() { return marriage.getFriendshipRings(); }
    public Marriage getMarriageInstance() { return marriage.getMarriageInstance(); }
    public void addPlayerRing(Ring ring) { marriage.addPlayerRing(ring); }
    public int getMarriageItemId() { return marriage.getMarriageItemId(); }
    public void setMarriageItemId(int marriageItemId) { marriage.setMarriageItemId(marriageItemId); }
    public int getPartnerId() { return marriage.getPartnerId(); }
    public void setPartnerId(int partnerId) { marriage.setPartnerId(partnerId); }
    public void notifyMapTransferToPartner(int mapid) { marriage.notifyMapTransferToPartner(mapid); }
    public void broadcastMarriageMessage() { marriage.broadcastMarriageMessage(); }

    // ── miniGame 门面 ──

    public MiniGame getMiniGame() { return miniGame.getMiniGame(); }
    public void setMiniGame(MiniGame miniGame) { this.miniGame.setMiniGame(miniGame); }
    public RockPaperScissor getRps() { return miniGame.getRPS(); }
    public void setRPS(RockPaperScissor rps) { miniGame.setRPS(rps); }
    public void closeMiniGame(boolean forceClose) { miniGame.closeMiniGame(forceClose); }
    public void closeRPS() { miniGame.closeRPS(); }
    public int getMiniGamePoints(MiniGameResult type, boolean omok) { return miniGame.getMiniGamePoints(type, omok); }
    public void setMiniGamePoints(Character visitor, int winnerslot, boolean omok) { miniGame.setMiniGamePoints(visitor, winnerslot, omok); }
    public int getOmokwins() { return miniGame.getOmokwins(); }
    public void setOmokwins(int omokwins) { miniGame.setOmokwins(omokwins); }
    public int getOmokties() { return miniGame.getOmokties(); }
    public void setOmokties(int omokties) { miniGame.setOmokties(omokties); }
    public int getOmoklosses() { return miniGame.getOmoklosses(); }
    public void setOmoklosses(int omoklosses) { miniGame.setOmoklosses(omoklosses); }
    public int getMatchcardwins() { return miniGame.getMatchcardwins(); }
    public void setMatchcardwins(int matchcardwins) { miniGame.setMatchcardwins(matchcardwins); }
    public int getMatchcardties() { return miniGame.getMatchcardties(); }
    public void setMatchcardties(int matchcardties) { miniGame.setMatchcardties(matchcardties); }
    public int getMatchcardlosses() { return miniGame.getMatchcardlosses(); }
    public void setMatchcardlosses(int matchcardlosses) { miniGame.setMatchcardlosses(matchcardlosses); }

    // ── level 门面 ──

    public int getLevel() { return level.getLevel(); }
    public void setLevel(int level) { this.level.setLevel(level); }
    public int getExp() { return level.getExp(); }
    public void setExp(int amount) { level.setExp(amount); }
    public int getGachaExp() { return level.getGachaExp(); }
    public void setGachaExp(int amount) { level.setGachaExp(amount); }
    public void gainExp(int gain) { level.gainExp(gain); }
    public void gainExp(int gain, boolean show, boolean inChat) { level.gainExp(gain, show, inChat); }
    public void gainExp(int gain, boolean show, boolean inChat, boolean white) { level.gainExp(gain, show, inChat, white); }
    public void gainExp(int gain, int party, boolean show, boolean inChat, boolean white) { level.gainExp(gain, party, show, inChat, white); }
    public void loseExp(int loss, boolean show, boolean inChat) { level.loseExp(loss, show, inChat); }
    public void loseExp(int loss, boolean show, boolean inChat, boolean white) { level.loseExp(loss, show, inChat, white); }
    public void gainGachaExp() { level.gainGachaExp(); }
    public void addGachaExp(int gain) { level.addGachaExp(gain); }
    public synchronized void levelUp(boolean takeexp) { level.levelUp(takeexp); }

    // ── fame 门面 ──

    public int getFame() { return fame.getFame(); }
    public void setFame(int fame) { this.fame.setFame(fame); }
    public void gainFame(int delta) { fame.gainFame(delta); }
    public boolean gainFame(int delta, Character fromPlayer, int mode) { return fame.gainFame(delta, fromPlayer, mode); }
    public void hasGivenFame(Character to) { fame.hasGivenFame(to); }
    public long getLastfametime() { return fame.getLastfametime(); }
    public void setLastfametime(long lastfametime) { fame.setLastfametime(lastfametime); }
    public List<Integer> getLastmonthfameids() { return fame.getLastmonthfameids(); }
    public void setLastmonthfameids(List<Integer> lastmonthfameids) { fame.setLastmonthfameids(lastmonthfameids); }

    // ── gm 门面 ──

    public boolean isGM() { return gm.isGM(); }
    public int gmLevel() { return gm.gmLevel(); }
    public void setGMLevel(int level) { gm.setGMLevel(level); }
    public void setGM(int level) { gm.setGM(level); }
    public boolean isHidden() { return gm.isHidden(); }
    public void hide(boolean hide, boolean login) { gm.hide(hide, login); }
    public void hide(boolean hide) { gm.hide(hide); }
    public void toggleHide(boolean login) { gm.toggleHide(login); }
    public boolean getWhiteChat() { return gm.getWhiteChat(); }
    public void toggleWhiteChat() { gm.toggleWhiteChat(); }

    // ── reborn 门面 ──

    public void setReborns(int value) { reborn.setReborns(value); }
    public void addReborns() { reborn.addReborns(); }
    public int getReborns() { return reborn.getReborns(); }
    public void executeRebornAsId(int jobId) { reborn.executeRebornAsId(jobId); }
    public void executeRebornAs(JobEnum job) { reborn.executeRebornAs(job); }

    // ── death 门面 ──

    public void respawn(int returnMap) { death.respawn(returnMap); }
    public void respawn(EventInstanceManager eim, int returnMap) { death.respawn(eim, returnMap); }

    // ── keybinding 门面 ──

    public Map<Integer, KeyBinding> getKeymap() { return keybinding.getKeymap(); }
    public void changeKeybinding(int key, KeyBinding keybinding) { this.keybinding.changeKeybinding(key, keybinding); }
    public void changeQuickslotKeybinding(byte[] aQuickslotKeyMapped) { keybinding.changeQuickslotKeybinding(aQuickslotKeyMapped); }
    public void sendKeymap() { keybinding.sendKeymap(); }
    public void sendQuickmap() { keybinding.sendQuickmap(); }
    public byte[] getQuickSlotLoaded() { return keybinding.getQuickSlotLoaded(); }
    public void setQuickSlotLoaded(byte[] quickSlotLoaded) { keybinding.setQuickSlotLoaded(quickSlotLoaded); }
    public void setQuickSlotKeyMapped(QuickslotBinding quickSlotKeyMapped) { keybinding.setQuickSlotKeyMapped(quickSlotKeyMapped); }

    // ── storage 门面 ──

    public Storage getStorage() { return storage.getStorage(); }
    public void setStorage(Storage storage) { this.storage.setStorage(storage); }
    public void setUsedStorage() { storage.setUsedStorage(); }

    // ── specialSkills 门面 ──

    public int getEnergyBar() { return specialSkills.getEnergyBar(); }
    public void setEnergyBar(int energyBar) { specialSkills.setEnergyBar(energyBar); }
    public int getBattleshipHp() { return specialSkills.getBattleshipHp(); }
    public boolean isRidingBattleship() { return specialSkills.isRidingBattleship(); }
    public void announceBattleshipHp() { specialSkills.announceBattleshipHp(); }
    public void decreaseBattleshipHp(int decrease) { specialSkills.decreaseBattleshipHp(decrease); }
    public void resetBattleshipHp() { specialSkills.resetBattleshipHp(); }
    public void handleEnergyChargeGain() { specialSkills.handleEnergyChargeGain(); }
    public void handleOrbconsume() { specialSkills.handleOrbconsume(); }

    // ── salon 门面 ──

    public int getHair() { return salon.getHair(); }
    public void setHair(int hair) { salon.setHair(hair); }
    public int getFace() { return salon.getFace(); }
    public void setFace(int face) { salon.setFace(face); }
    public SkinColor getSkinColor() { return salon.getSkinColor(); }
    public void setSkinColor(SkinColor skinColor) { salon.setSkinColor(skinColor); }
    public void changeFaceExpression(int emote) { salon.changeFaceExpression(emote); }

    // ── buddy 门面 ──

    public BuddyList getBuddylist() { return buddy.getBuddylist(); }
    public void setBuddylist(BuddyList buddylist) { buddy.setBuddylist(buddylist); }
    public void deleteBuddy(int otherCid) { buddy.deleteBuddy(otherCid); }
    public void setBuddyCapacity(int capacity) { buddy.setBuddyCapacity(capacity); }
}
