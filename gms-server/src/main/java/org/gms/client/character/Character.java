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
import org.gms.client.CharacterNameAndId;
import org.gms.client.Client;
import org.gms.client.Disease;
import org.gms.client.Family;
import org.gms.client.FamilyEntry;
import org.gms.client.Job;
import org.gms.client.MonsterBook;
import org.gms.client.Mount;
import org.gms.client.QuestStatus;
import org.gms.client.Ring;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.SkillMacro;
import org.gms.client.SkinColor;
import org.gms.client.Stat;
import org.gms.client.autoban.AutobanManager;
import org.gms.client.creator.CharacterFactoryRecipe;
import org.gms.client.inventory.*;
import org.gms.client.inventory.Equip.StatUpgrade;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.client.keybind.KeyBinding;
import org.gms.client.keybind.QuickslotBinding;
import org.gms.config.GameConfig;
import org.gms.constants.game.DelayedQuestUpdate;
import org.gms.constants.game.ExpTable;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MapId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.net.ServerConstants;
import org.gms.constants.skills.*;
import org.gms.constants.string.ExtendKey;
import org.gms.dao.entity.*;
import org.gms.exception.NotEnabledException;
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
import org.gms.scripting.item.ItemScriptManager;
import org.gms.server.*;
import org.gms.server.ExpLogger.ExpLogRecord;
import org.gms.server.ItemInformationProvider.ScriptedItem;
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
import org.gms.server.quest.medal.DynamicHairMedal;
import org.gms.service.*;
import org.gms.util.*;
import org.gms.util.packets.WeddingPackets;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import static org.gms.client.character.BaseStat.*;

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
    private int level;
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
    private int hair;
    @Setter
    @Getter
    private int face;
    @Setter
    @Getter
    private int fame;
    private final Lock fameLock = new ReentrantLock(true);   // applyFame 的 fame 读改写专用（原误用 petLock）
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
    @Setter
    private int energyBar;
    private int gmLevel;
    @Getter
    private int ci = 0;
    @Getter
    private FamilyEntry familyEntry;
    @Setter
    @Getter
    private int familyId;
    @Setter
    private int bookCover;
    @Setter
    @Getter
    int battleshipHp = 0;
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
    private int omokwins;
    @Getter
    @Setter
    private int omokties;
    @Getter
    @Setter
    private int omoklosses;
    @Getter
    @Setter
    private int matchcardwins;
    @Getter
    @Setter
    private int matchcardties;
    @Getter
    @Setter
    private int matchcardlosses;
    @Getter
    @Setter
    private int owlSearch;
    @Setter
    @Getter
    private long lastfametime;
    @Setter
    @Getter
    private long lastUsedCashItem;
    private long lastExpression = 0;
    @Getter
    private boolean hidden;
    private boolean berserk, whiteChat = false;

    @Setter
    boolean canRecvPartySearchInvite = true;    // 包内可见：CharacterJob.changeJob 读取
    private boolean usedSafetyCharm = false;
    @Getter
    @Setter
    private int linkedLevel = 0;
    @Getter
    @Setter
    private String linkedName = null;
    @Getter
    @Setter
    private boolean finishedDojoTutorial;
    private boolean usedStorage = false;
    @Getter
    @Setter
    private String name;
    private String chalktext;
    private String commandtext;
    @Getter
    @Setter
    private String search = null;
    final AtomicBoolean awayFromWorld = new AtomicBoolean(true);  // player is online, but on cash shop or mts
    private final AtomicInteger exp = new AtomicInteger();
    private final AtomicInteger gachaExp = new AtomicInteger();
    private final AtomicInteger meso = new AtomicInteger();
    private long totalExpGained = 0;
    @Getter
    @Setter
    private BuddyList buddylist;
    private EventInstanceManager eventInstance = null;
    @Getter
    @Setter
    Client client;
    @Getter
    @Setter
    private Messenger messenger = null;
    @Getter
    @Setter
    private MiniGame miniGame;
    @Getter
    private RockPaperScissor rps;
    @Getter
    @Setter
    private Mount mapleMount;
    @Getter
    @Setter
    private Shop shop = null;
    @Getter
    @Setter
    private SkinColor skinColor = SkinColor.NORMAL;
    @Getter
    @Setter
    private Storage storage = null;
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
    @Setter
    @Getter
    private List<Integer> lastmonthfameids;
    private WeakReference<MapleMap> ownedMap = new WeakReference<>(null);
    private final Set<Monster> controlled = new LinkedHashSet<>();
    private final Map<Integer, String> entered = new LinkedHashMap<>();
    private final Set<MapObject> visibleMapObjects = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final CharacterSkills skills = new CharacterSkills(this);

    private final Map<Integer, KeyBinding> keymap = new LinkedHashMap<>();

    public Map<Integer, KeyBinding> getKeymap() { return keymap; }
    final Map<Integer, Summon> summons = new LinkedHashMap<>();
    @Getter
    @Setter
    private byte[] quickSlotLoaded;
    @Setter
    private QuickslotBinding quickSlotKeyMapped;
    ScheduledFuture<?> dragonBloodSchedule;
    private ScheduledFuture<?> hpDecreaseTask;
    ScheduledFuture<?> beholderHealingSchedule, beholderBuffSchedule, berserkSchedule;
    ScheduledFuture<?> recoveryTask = null;
    ScheduledFuture<?> extraRecoveryTask = null;
    private ScheduledFuture<?> pendantOfSpirit = null; //1122017
    private ScheduledFuture<?> cpqSchedule = null;

    private ScheduledFuture<?> FamilyBuffTimer = null;
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
    private boolean allowExpGain = true;
    private byte pendantExp = 0;
    private final List<Integer> trockmaps = new ArrayList<>();
    private final List<Integer> viptrockmaps = new ArrayList<>();
    @Getter
    private Map<String, Events> events = new LinkedHashMap<>();
    @Setter
    @Getter
    private Dragon dragon = null;
    @Setter
    private Ring marriageRing;
    @Setter
    @Getter
    private int marriageItemId = -1;
    @Setter
    @Getter
    private int partnerId = -1;
    private final List<Ring> crushRings = new ArrayList<>();
    private final List<Ring> friendshipRings = new ArrayList<>();
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
    @Setter
    private long lastExpGainTime;
    private boolean pendingNameChange; //only used to change name on logout, not to be relied upon elsewhere
    @Getter
    @Setter
    private long loginTime;
    @Setter
    @Getter
    private boolean chasing = false;

    @Getter
    private boolean familyBuff = false;

    // 获取 FamilyExp 的值
    @Getter
    private float familyExp = 1;
    @Getter
    private float familyDrop = 1;
    static final CharacterService characterService = ServerManager.getApplicationContext().getBean(CharacterService.class);
    private static final NameChangeService nameChangeService = ServerManager.getApplicationContext().getBean(NameChangeService.class);
    private static final WorldTransferService worldTransferService = ServerManager.getApplicationContext().getBean(WorldTransferService.class);
    static final AccountService accountService = ServerManager.getApplicationContext().getBean(AccountService.class);    // 包内可见：CharacterAntiCheat.ban/block 调用
    static final HpMpAlertService hpMpAlertService = ServerManager.getApplicationContext().getBean(HpMpAlertService.class);    // 包内可见：CharacterStats.applyHpMpChange 调用
    private static final InventoryService inventoryService = ServerManager.getApplicationContext().getBean(InventoryService.class);

    public int getClientMaxHp() {
        return stats.clientMaxHp;
    }

    public int getClientMaxMp() {
        return stats.clientMaxMp;
    }

    /** 各技能原始时间戳，仅被 >= MIN_INTERVAL 的正常包更新，暴发包透明通过 */
    private final ConcurrentHashMap<Integer, Long> normalAttackTimes = new ConcurrentHashMap<>();

    /**
     * 获取指定技能距上次攻击的间隔毫秒数，并更新最后攻击时间。
     * 间隔 < MIN_INTERVAL 时不更新时间戳，视为网络抖动透明跳过。
     * 首次调用或时钟回退时返回 Long.MAX_VALUE，本次不参与间隔判定。
     */
    public long getAttackInterval(int skillId, long now) {
        AtomicLong intervalRef = new AtomicLong(Long.MAX_VALUE);
        normalAttackTimes.compute(skillId, (ignored, prevTime) -> {
            long prev = prevTime != null ? prevTime : 0L;
            if (prev > 0L && now > prev) {
                intervalRef.set(now - prev);
            }
            long interval = intervalRef.get();
            if (interval != Long.MAX_VALUE && interval < MIN_INTERVAL) {
                return prev;
            }
            return Math.max(prev, now);
        });
        return intervalRef.get();
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
        stats.rLock.lock();
        try {
            return stats.hp > 0;
        } finally {
            stats.rLock.unlock();
        }
    }

    public int getHp() {
        stats.rLock.lock();
        try {
            return stats.hp;
        } finally {
            stats.rLock.unlock();
        }
    }

    public int getMp() {
        stats.rLock.lock();
        try {
            return stats.mp;
        } finally {
            stats.rLock.unlock();
        }
    }

    public int getMaxHp() {
        stats.rLock.lock();
        try {
            return stats.maxHp;
        } finally {
            stats.rLock.unlock();
        }
    }

    public int getMaxMp() {
        stats.rLock.lock();
        try {
            return stats.maxMp;
        } finally {
            stats.rLock.unlock();
        }
    }

    public int getCurrentMaxHp() {
        return stats.localMaxHp;
    }

    public int getCurrentMaxMp() {
        return stats.localMaxMp;
    }

    public boolean assignStrDexIntLuk(int deltaStr, int deltaDex, int deltaInt, int deltaLuk) {
        Integer[] delta = new Integer[BASE_STAT_COUNT];
        delta[STR] = deltaStr;
        delta[DEX] = deltaDex;
        delta[INT] = deltaInt;
        delta[LUK] = deltaLuk;
        return ap.assignAttrs(delta);
    }

    /** 四维全部设为 x（管理命令用） */
    public void updateStrDexIntLuk(int x) {
        StatsUpdate u = new StatsUpdate();
        for (int i = 0; i < BASE_STAT_COUNT; i++) {
            u.setAttr(i, x);
        }
        stats.applyUpdate(u);
    }

    private void setRemainingSp(int[] sps) {
        sp.setRemainingSp(sps);
    }

    private void updateRemainingSp(int remainingSp, int jobId) {
        sp.changeRemainingSp(remainingSp, jobId, false);
    }

    public void setHair(int hair) {
        int oldHair = this.hair;
        this.hair = hair;
        DynamicHairMedal.onHairChanged(this, oldHair, hair);
    }

    public static Character getDefault(Client c) {
        Character ret = new Character();
        ret.client = c;
        ret.setGMLevel(0);
        ret.stats.hp = 50;
        ret.stats.setMaxHp(50);
        ret.stats.mp = 5;
        ret.stats.setMaxMp(5);
        ret.stats.attrs[STR] = 12;
        ret.stats.attrs[DEX] = 5;
        ret.stats.attrs[INT] = 4;
        ret.stats.attrs[LUK] = 4;
        ret.setMap((MapleMap) null);
        ret.setJob(Job.BEGINNER);
        ret.level = 1;
        ret.accountId = c.getAccID();
        ret.buddylist = new BuddyList(20);
        ret.mapleMount = null;
        ret.getInventory(InventoryType.EQUIP).setSlotLimit(24);
        ret.getInventory(InventoryType.USE).setSlotLimit(24);
        ret.getInventory(InventoryType.SETUP).setSlotLimit(24);
        ret.getInventory(InventoryType.ETC).setSlotLimit(24);

        // Select a keybinding method
        boolean useCustomKeySet = GameConfig.getServerBoolean("use_custom_keyset");
        int[] selectedKey = GameConstants.getCustomKey(useCustomKeySet);
        int[] selectedType = GameConstants.getCustomType(useCustomKeySet);
        int[] selectedAction = GameConstants.getCustomAction(useCustomKeySet);

        for (int i = 0; i < selectedKey.length; i++) {
            ret.keymap.put(selectedKey[i], new KeyBinding(selectedType[i], selectedAction[i]));
        }

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

        if (canRecvPartySearchInvite) {
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

    public void updatePartySearchAvailability(boolean pSearchAvailable) {
        if (pSearchAvailable) {
            if (canRecvPartySearchInvite && getParty() == null) {
                this.getWorldServer().getPartySearchCoordinator().attachPlayer(this);
            }
        } else {
            if (canRecvPartySearchInvite) {
                this.getWorldServer().getPartySearchCoordinator().detachPlayer(this);
            }
        }
    }

    public boolean toggleRecvPartySearchInvite() {
        canRecvPartySearchInvite = !canRecvPartySearchInvite;

        if (canRecvPartySearchInvite) {
            updatePartySearchAvailability(getParty() == null);
        } else {
            this.getWorldServer().getPartySearchCoordinator().detachPlayer(this);
        }

        return canRecvPartySearchInvite;
    }

    public boolean isRecvPartySearchInviteEnabled() {
        return canRecvPartySearchInvite;
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

    public Ring getRingById(int id) {
        Optional<Ring> ringOptional = getCrushRings().stream().filter(ring -> ring.getRingId() == id).findFirst();
        if (ringOptional.isPresent()) {
            return ringOptional.get();
        }
        ringOptional = getFriendshipRings().stream().filter(ring -> ring.getRingId() == id).findFirst();
        if (ringOptional.isPresent()) {
            return ringOptional.get();
        }
        if (marriageRing != null && marriageRing.getRingId() == id) {
            return marriageRing;
        }
        return null;
    }

    public int getRelationshipId() {
        return getWorldServer().getRelationshipId(id);
    }

    public boolean isMarried() {
        return marriageRing != null && partnerId > 0;
    }

    public boolean hasJustMarried() {
        EventInstanceManager eim = getEventInstance();
        if (eim != null) {
            String prop = eim.getProperty("groomId");

            if (prop != null) {
                return (Integer.parseInt(prop) == id || eim.getIntProperty("brideId") == id) &&
                        (getMapId() == MapId.CHAPEL_WEDDING_ALTAR || getMapId() == MapId.CATHEDRAL_WEDDING_ALTAR);
            }
        }

        return false;
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
        if (job.isA(Job.THIEF) && weapon == WeaponType.DAGGER_OTHER) {
            weapon = WeaponType.DAGGER_THIEVES;
        }

        if (weapon == WeaponType.BOW || weapon == WeaponType.CROSSBOW || weapon == WeaponType.GUN) {
            mainstat = stats.localAttrs[DEX];
            secondarystat = stats.localAttrs[STR];
        } else if (weapon == WeaponType.CLAW || weapon == WeaponType.DAGGER_THIEVES) {
            mainstat = stats.localAttrs[LUK];
            secondarystat = stats.localAttrs[DEX] + stats.localAttrs[STR];
        } else {
            mainstat = stats.localAttrs[STR];
            secondarystat = stats.localAttrs[DEX];
        }
        return (int) Math.ceil(((weapon.getMaxDamageMultiplier() * mainstat + secondarystat) / 100.0) * watk);
    }

    public int calculateMaxBaseDamage(int watk) {
        int maxbasedamage;
        Item weapon_item = getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        if (weapon_item != null) {
            maxbasedamage = calculateMaxBaseDamage(watk, ItemInformationProvider.getInstance().getWeaponType(weapon_item.getItemId()));
        } else {
            if (job.isA(Job.PIRATE) || job.isA(Job.THUNDERBREAKER1)) {
                double weapMulti = 3;
                if (job.getId() % 100 != 0) {
                    weapMulti = 4.2;
                }

                int attack = (int) Math.min(Math.floor((2D * getLevel() + 31) / 3), 31);
                maxbasedamage = (int) Math.ceil((stats.localAttrs[STR] * weapMulti + stats.localAttrs[DEX]) * attack / 100.0);
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

    public void hide(boolean hide, boolean login) {
        if (isGM() && hide != this.hidden) {
            if (!hide) {
                this.hidden = false;
                sendPacket(PacketCreator.getGMEffect(0x10, (byte) 0));
                List<EffectType> dsstat = Collections.singletonList(EffectType.DARKSIGHT);
                getMap().broadcastGMMessage(this, PacketCreator.cancelForeignBuff(id, dsstat), false);
                getMap().broadcastSpawnPlayerMapObjectMessage(this, this, false);

                for (Summon ms : this.getSummonsValues()) {
                    getMap().broadcastNONGMMessage(this, PacketCreator.spawnSummon(ms, false), false);
                }

                for (MapObject mo : this.getMap().getMonsters()) {
                    Monster m = (Monster) mo;
                    m.aggroUpdateController();
                }
            } else {
                this.hidden = true;
                sendPacket(PacketCreator.getGMEffect(0x10, (byte) 1));
                if (!login) {
                    getMap().broadcastNONGMMessage(this, PacketCreator.removePlayerFromMap(getId()), false);
                }
                List<Pair<EffectType, Integer>> ldsstat = Collections.singletonList(new Pair<EffectType, Integer>(EffectType.DARKSIGHT, 0));
                getMap().broadcastGMMessage(this, PacketCreator.giveForeignBuff(id, ldsstat), false);
                this.releaseControlledMonsters();
            }
            enableActions();
        }
    }

    public void hide(boolean hide) {
        hide(hide, false);
    }

    public void toggleHide(boolean login) {
        hide(!hidden, login);
    }

    public void cancelMagicDoor() {
        List<EffectStatus> effects = getAllStatups();
        for (EffectStatus effect : effects) {
            if (effect.getData().isMagicDoor()) {
                cancelEffect(effect.getData(), false);
                break;
            }
        }
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
        buddylist.broadcast(packet, getWorldServer().getPlayerStorage());
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

    public void changeKeybinding(int key, KeyBinding keybinding) {
        if (keybinding.getType() != 0) {
            keymap.put(key, keybinding);
        } else {
            keymap.remove(key);
        }
    }

    public void changeQuickslotKeybinding(byte[] aQuickslotKeyMapped) {
        this.quickSlotKeyMapped = new QuickslotBinding(aQuickslotKeyMapped);
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

    public void notifyMapTransferToPartner(int mapid) {
        if (partnerId > 0) {
            final Character partner = getWorldServer().getPlayerStorage().getCharacterById(partnerId);
            if (partner != null && !partner.isAwayFromWorld()) {
                partner.sendPacket(WeddingPackets.OnNotifyWeddingPartnerTransfer(id, mapid));
            }
        }
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
        if (job.equalsJob(Job.DARKKNIGHT)) {
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

    public final void pickupItem(MapObject ob) {
        pickupItem(ob, -1);
    }

    public final void pickupItem(MapObject ob, int petIndex) {     // yes, one picks the MapObject, not the MapItem     //是的，选择MapObject，而不是MapItem
        if (ob == null) {                                               // pet index refers to the one picking up the item      //宠物指数是指捡起物品的人
            return;
        }

        if (ob instanceof MapItem mapitem) {
            if (System.currentTimeMillis() - mapitem.getDropTime() < 400) {
                enableActions();
                return;
            }

            // canBePickedBy 读/写 owner 字段,必须持 itemLock
            mapitem.lockItem();
            try {
                if (!mapitem.canBePickedBy(this)) {
                    enableActions();
                    return;
                }
            } finally {
                mapitem.unlockItem();
            }

            List<Character> mpcs = new LinkedList<>();
            if (mapitem.getMeso() > 0 && !mapitem.isPickedUp()) {
                mpcs = getPartyMembersOnSameMap();
            }

            ScriptedItem itemScript = null;
            mapitem.lockItem();
            try {
                if (mapitem.isPickedUp()) {
                    sendPacket(PacketCreator.showItemUnavailable());
                    enableActions();
                    return;
                }

                boolean isPet = petIndex > -1;
                final Packet pickupPacket = PacketCreator.removeItemFromMap(mapitem.getObjectId(), (isPet) ? 5 : 2, this.getId(), isPet, petIndex);

                Item mItem = mapitem.getItem();
                boolean hasSpaceInventory = true;
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                if (ItemId.isNxCard(mapitem.getItemId()) || mapitem.getMeso() > 0 || ii.isConsumeOnPickup(mapitem.getItemId()) || (hasSpaceInventory = InventoryManipulator.checkSpace(client, mapitem.getItemId(), mItem.getQuantity(), mItem.getOwner()))) {
                    int mapId = this.getMapId();

                    if ((MapId.isSelfLootableOnly(mapId))) {//happyville trees and guild PQ
                        if (!mapitem.isPlayerDrop() || mapitem.getDropper().getObjectId() == client.getPlayer().getObjectId()) {
                            if (mapitem.getMeso() > 0) {
                                if (!mpcs.isEmpty()) {
                                    int mesosamm = mapitem.getMeso() / mpcs.size();
                                    for (Character partymem : mpcs) {
                                        if (partymem.isLoggedInWorld()) {
                                            partymem.gainMeso(mesosamm, true, true, false);
                                        }
                                    }
                                } else {
                                    this.gainMeso(mapitem.getMeso(), true, true, false);
                                }

                                this.getMap().pickItemDrop(pickupPacket, mapitem);
                            } else if (ItemId.isNxCard(mapitem.getItemId())) {
                                // Add NX to account, show effect and make item disappear   //添加点券到账户，是否展示捡到点券，并移除物品
                                int nxGain = (mapitem.getItemId() == ItemId.NX_CARD_100 ? 100 : 250) * mItem.getQuantity(); //使点券支持按数量相乘
                                this.getCashShop().gainCash(CashShop.NX_CREDIT, nxGain);

                                if (GameConfig.getServerBoolean("use_announce_nx_coupon_loot")) {       //捡到点券是否展示
                                    showHint(I18nUtil.getMessage("Character.pickupItem.message1", nxGain, this.getCashShop().getCash(CashShop.NX_CREDIT)), 300);
                                    //showHint("捡到 #e#b" + nxGain + " NX#k#n (" + this.getCashShop().getCash(CashShop.NX_CREDIT) + " NX)", 300);
                                }

                                this.getMap().pickItemDrop(pickupPacket, mapitem);
                            } else if (InventoryManipulator.addFromDrop(client, mItem, true)) {
                                this.getMap().pickItemDrop(pickupPacket, mapitem);
                            } else {
                                enableActions();
                                return;
                            }
                        } else {
                            sendPacket(PacketCreator.showItemUnavailable());
                            enableActions();
                            return;
                        }
                        enableActions();
                        return;
                    }

                    if (!quests.needQuestItem(mapitem.getQuest(), mapitem.getItemId())) {
                        sendPacket(PacketCreator.showItemUnavailable());
                        enableActions();
                        return;
                    }

                    if (mapitem.getMeso() > 0) {
                        if (!mpcs.isEmpty()) {
                            int mesosamm = mapitem.getMeso() / mpcs.size();
                            for (Character partymem : mpcs) {
                                if (partymem.isLoggedInWorld()) {
                                    partymem.gainMeso(mesosamm, true, true, false);
                                }
                            }
                        } else {
                            this.gainMeso(mapitem.getMeso(), true, true, false);
                        }
                    } else if (mItem.getItemId() / 10000 == 243) {
                        ScriptedItem info = ii.getScriptedItemInfo(mItem.getItemId());
                        if (info != null && info.runOnPickup()) {
                            itemScript = info;
                        } else {
                            if (!InventoryManipulator.addFromDrop(client, mItem, true)) {
                                enableActions();
                                return;
                            }
                        }
                    } else if (ItemId.isNxCard(mapitem.getItemId())) {
                        // Add NX to account, show effect and make item disappear
                        int nxGain = (mapitem.getItemId() == ItemId.NX_CARD_100 ? 100 : 250) * mItem.getQuantity(); //使点券支持按数量相乘
                        this.getCashShop().gainCash(CashShop.NX_CREDIT, nxGain);

                        if (GameConfig.getServerBoolean("use_announce_nx_coupon_loot")) {       //捡到点券是否展示
                            showHint(I18nUtil.getMessage("Character.pickupItem.message1", nxGain, this.getCashShop().getCash(CashShop.NX_CREDIT)), 300);
                            //showHint("捡到 #e#b" + nxGain + " NX#k#n (" + this.getCashShop().getCash(CashShop.NX_CREDIT) + " NX)", 300);
                        }
                    } else if (applyConsumeOnPickup(mItem.getItemId())) {//此段判断为处理捡取治疗道具和怪物卡加入图鉴
                    } else if (InventoryManipulator.addFromDrop(client, mItem, true)) {
                        if (mItem.getItemId() == ItemId.ARPQ_SPIRIT_JEWEL) {
                            updateAriantScore();
                        }
                    } else {
                        enableActions();
                        return;
                    }

                    this.getMap().pickItemDrop(pickupPacket, mapitem);
                } else if (!hasSpaceInventory) {
                    sendPacket(PacketCreator.getInventoryFull());
                    sendPacket(PacketCreator.getShowInventoryFull());
                }
            } finally {
                mapitem.unlockItem();
            }

            if (itemScript != null) {
                ItemScriptManager ism = ItemScriptManager.getInstance();
                ism.runItemScript(client, itemScript);
            }
        }
        enableActions();
    }

    public boolean isRidingBattleship() {
        Integer bv = getBuffedValue(EffectType.MONSTER_RIDING);
        return bv != null && bv.equals(Corsair.BATTLE_SHIP);
    }

    public void announceBattleshipHp() {
        sendPacket(PacketCreator.skillCooldown(5221999, battleshipHp));
    }

    public void decreaseBattleshipHp(int decrease) {
        this.battleshipHp -= decrease;
        if (battleshipHp <= 0) {
            Skill battleship = SkillFactory.getSkill(Corsair.BATTLE_SHIP);
            int cooldown = battleship.getEffect(getSkillLevel(battleship)).getCooldown();
            sendPacket(PacketCreator.skillCooldown(Corsair.BATTLE_SHIP, cooldown));
            addCooldown(Corsair.BATTLE_SHIP, Server.getInstance().getCurrentTime(), SECONDS.toMillis(cooldown));
            removeCooldown(5221999);
            cancelEffectFromBuffStat(EffectType.MONSTER_RIDING);
        } else {
            announceBattleshipHp();
            addCooldown(5221999, 0, Long.MAX_VALUE);
        }
    }

    public void decreaseReports() {
        this.possibleReports--;
    }

    private void nextPendingRequest(Client c) {
        CharacterNameAndId pendingBuddyRequest = c.getPlayer().getBuddylist().pollPendingRequest();
        if (pendingBuddyRequest != null) {
            c.sendPacket(PacketCreator.requestBuddylistAdd(pendingBuddyRequest.getId(), c.getPlayer().getId(), pendingBuddyRequest.getName()));
        }
    }

    private void notifyRemoteChannel(Client c, int remoteChannel, int otherCid, BuddyList.BuddyOperation operation) {
        Character player = c.getPlayer();
        if (remoteChannel != -1) {
            c.getWorldServer().buddyChanged(otherCid, player.getId(), player.getName(), c.getChannel(), operation);
        }
    }

    public void deleteBuddy(int otherCid) {
        BuddyList bl = getBuddylist();

        if (bl.containsVisible(otherCid)) {
            notifyRemoteChannel(client, getWorldServer().find(otherCid), otherCid, BuddyList.BuddyOperation.DELETED);
        }
        bl.remove(otherCid);
        sendPacket(PacketCreator.updateBuddylist(getBuddylist().getBuddies()));
        nextPendingRequest(client);
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

            if (Character.this.getHp() < stats.localMaxHp) {
                if (healHP > 0) {
                    sendPacket(PacketCreator.showOwnRecovery(healHP));
                    getMap().broadcastMessage(Character.this, PacketCreator.showRecovery(id, healHP), false);
                }
            }

            addMPHP(healHP, healMP);
        }, healInterval, healInterval);
    }

    public void dispel() {
        if (!(GameConfig.getServerBoolean("use_undispel_holy_shield") && this.hasActiveBuff(Bishop.HOLY_SHIELD))) {
            List<EffectStatus> effects = getAllStatups();
            for (EffectStatus effect : effects) {
                if (effect.getData().isSkill()) {
                    if (effect.getData().getBuffSourceId() != Aran.COMBO_ABILITY) { // check discovered thanks to Croosade dev team
                        cancelEffect(effect.getData(), false);
                    }
                }
            }
        }
    }

    public void dispelSkill(int skillid) {
        List<EffectStatus> effects = getAllStatups();
        for (EffectStatus effect : effects) {
            if (skillid == 0) {
                if (effect.getData().isSkill() && (effect.getData().getSourceId() % 10000000 == 1004 || dispelSkills(effect.getData().getSourceId()))) {
                    cancelEffect(effect.getData(), false);
                }
            } else if (effect.getData().isSkill() && effect.getData().getSourceId() == skillid) {
                cancelEffect(effect.getData(), false);
            }
        }
    }

    private static boolean dispelSkills(int skillid) {
        return switch (skillid) {
            case DarkKnight.BEHOLDER, FPArchMage.ELQUINES, ILArchMage.IFRIT, Priest.SUMMON_DRAGON, Bishop.BAHAMUT,
                 Ranger.PUPPET, Ranger.SILVER_HAWK, Sniper.PUPPET, Sniper.GOLDEN_EAGLE, Hermit.SHADOW_PARTNER -> true;
            default -> false;
        };
    }

    public void changeFaceExpression(int emote) {
        long timeNow = Server.getInstance().getCurrentTime();
        // Client allows changing every 2 seconds. Give it a little bit of overhead for packet delays.
        if (timeNow - lastExpression > 1500) {
            lastExpression = timeNow;
            getMap().broadcastMessage(this, PacketCreator.facialExpression(this, emote), false);
        }
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

    public void gainGachaExp() {
        int expgain = 0;
        long currentgexp = gachaExp.get();

        int levelUpNeed = ExpTable.getExpNeededForLevel(level) - exp.get();
        if (currentgexp >= levelUpNeed) {
            expgain += Math.max(0, levelUpNeed);

            int nextneed = ExpTable.getExpNeededForLevel(level + 1);
            if (currentgexp - expgain >= nextneed) {
                expgain += nextneed;
            }

            this.gachaExp.set((int) (currentgexp - expgain));
        } else {
            expgain = this.gachaExp.getAndSet(0);
        }
        gainExp(expgain, false, true);
        updateSingleStat(Stat.GACHAEXP, this.gachaExp.get());
    }

    public void addGachaExp(int gain) {
        updateSingleStat(Stat.GACHAEXP, gachaExp.addAndGet(gain));
    }

    public void gainExp(int gain) {
        gainExp(gain, true, true);
    }

    public void gainExp(int gain, boolean show, boolean inChat) {
        gainExp(gain, show, inChat, true);
    }

    public void gainExp(int gain, boolean show, boolean inChat, boolean white) {
        gainExp(gain, 0, show, inChat, white);
    }

    public void gainExp(int gain, int party, boolean show, boolean inChat, boolean white) {
        if (hasDisease(Disease.CURSE)) {
            gain *= 0.5;
            party *= 0.5;
        }

        if (gain < 0) {
            gain = Integer.MAX_VALUE;   // integer overflow, heh.
        }

        if (party < 0) {
            party = Integer.MAX_VALUE;  // integer overflow, heh.
        }

        int equip = (int) Math.min((long) (gain / 10) * pendantExp, Integer.MAX_VALUE);

        gainExpInternal(gain, equip, party, show, inChat, white);
    }

    public void loseExp(int loss, boolean show, boolean inChat) {
        loseExp(loss, show, inChat, true);
    }

    public void loseExp(int loss, boolean show, boolean inChat, boolean white) {
        gainExpInternal(-loss, 0, 0, show, inChat, white);
    }

    private void announceExpGain(long gain, int equip, int party, boolean inChat, boolean white) {
        gain = Math.min(gain, Integer.MAX_VALUE);
        if (gain == 0) {
            if (party == 0) {
                return;
            }

            gain = party;
            party = 0;
            white = false;
        }

        sendPacket(PacketCreator.getShowExpGain((int) gain, equip, party, inChat, white));
    }

    private synchronized void gainExpInternal(long gain, int equip, int party, boolean show, boolean inChat, boolean white) {   // need of method synchonization here detected thanks to MedicOP
        long total = Math.max(gain + equip + party, -exp.get());

        if (level < getMaxLevel() && (allowExpGain || this.getEventInstance() != null)) {
            long leftover = 0;
            long nextExp = exp.get() + total;

            if (nextExp > (long) Integer.MAX_VALUE) {
                total = Integer.MAX_VALUE - exp.get();
                leftover = nextExp - Integer.MAX_VALUE;
            }
            updateSingleStat(Stat.EXP, exp.addAndGet((int) total));
            totalExpGained += total;
            if (show) {
                announceExpGain(gain, equip, party, inChat, white);
            }
            while (exp.get() >= ExpTable.getExpNeededForLevel(level)) {
                levelUp(true);

                String msg = I18nUtil.getMessage("Character.levelUp.globalNotice", getName(), getMap().getMapName(), getLevel());
                if (GameConfig.getServerBoolean("use_announce_global_level_up") && !isGM()) {
                    for (Character player : getWorldServer().getPlayerStorage().getAllCharacters()) {
                        // 如果玩家在商城，将会以弹窗的形式发送，一堆弹窗会把玩家逼疯！
                        if (player.getCashShop().isOpened()) {
                            continue;
                        }
                        player.dropMessage(6, msg);
                    }
                    log.info(msg);
                }
                if (level == getMaxLevel()) {
                    setExp(0);
                    updateSingleStat(Stat.EXP, 0);
                    break;
                }
                if (GameConfig.getServerBoolean("use_level_up_protect")) break;
            }

            if (leftover > 0) {
                gainExpInternal(leftover, equip, party, false, inChat, white);
            } else {
                lastExpGainTime = System.currentTimeMillis();

                if (GameConfig.getServerBoolean("use_exp_gain_log")) {
                    ExpLogRecord expLogRecord = new ExpLogger.ExpLogRecord(
                            getWorldServer().getExpRate(),
                            getCouponExpRate(),
                            totalExpGained,
                            exp.get(),
                            new Timestamp(lastExpGainTime),
                            id
                    );
                    ExpLogger.putExpLogRecord(expLogRecord);
                }

                totalExpGained = 0;
            }
        }
    }

    private Pair<Integer, Integer> applyFame(int delta) {
        fameLock.lock();
        try {
            int newFame = fame + delta;
            if (newFame < -30000) {
                delta = -(30000 + fame);
            } else if (newFame > 30000) {
                delta = 30000 - fame;
            }

            fame += delta;
            return new Pair<>(fame, delta);
        } finally {
            fameLock.unlock();
        }
    }

    public void gainFame(int delta) {
        gainFame(delta, null, 0);
    }

    public boolean gainFame(int delta, Character fromPlayer, int mode) {
        Pair<Integer, Integer> fameRes = applyFame(delta);
        delta = fameRes.getRight();
        if (delta != 0) {
            int thisFame = fameRes.getLeft();
            updateSingleStat(Stat.FAME, thisFame);

            if (fromPlayer != null) {
                fromPlayer.sendPacket(PacketCreator.giveFameResponse(mode, getName(), thisFame));
                sendPacket(PacketCreator.receiveFame(mode, fromPlayer.getName()));
            } else {
                sendPacket(PacketCreator.getShowFameGain(delta));
            }

            return true;
        } else {
            return false;
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
            updateSingleStat(Stat.MESO, (int) nextMeso, enableActions);
            if (show) {
                sendPacket(PacketCreator.getShowMesoGain(gain, inChat));
            }
        } else {
            enableActions();
        }
    }

    List<EffectStatus> getAllStatups() {
        return buffs.getAllEffects();
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

    public List<Ring> getCrushRings() {
        synchronized (crushRings) {
            Collections.sort(crushRings);
            return new ArrayList<>(crushRings);
        }
    }

    public EventInstanceManager getEventInstance() {
        evtLock.lock();
        try {
            return eventInstance;
        } finally {
            evtLock.unlock();
        }
    }

    public Marriage getMarriageInstance() {
        return (Marriage) getEventInstance();
    }

    public int getExp() {
        return exp.get();
    }

    public int getGachaExp() {
        return gachaExp.get();
    }

    public Family getFamily() {
        if (familyEntry != null) {
            return familyEntry.getFamily();
        } else {
            return null;
        }
    }

    public void setFamilyEntry(FamilyEntry entry) {
        if (entry != null) {
            setFamilyId(entry.getFamily().getID());
        }
        this.familyEntry = entry;
    }

    public void setUsedStorage() {
        usedStorage = true;
    }

    public List<Ring> getFriendshipRings() {
        synchronized (friendshipRings) {
            Collections.sort(friendshipRings);
            return new ArrayList<>(friendshipRings);
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

    public Ring getMarriageRing() {
        return partnerId > 0 ? marriageRing : null;
    }

    public int getTotalStr() {
        return stats.localAttrs[STR];
    }

    public int getTotalDex() {
        return stats.localAttrs[DEX];
    }

    public int getTotalInt() {
        return stats.localAttrs[INT];
    }

    public int getTotalLuk() {
        return stats.localAttrs[LUK];
    }

    public int getTotalMagic() {
        return stats.localmagic;
    }

    public int getTotalWatk() {
        return stats.localwatk;
    }

    public int getMaxClassLevel() {
        return isCygnus() ? 120 : 200;
    }

    public int getMaxLevel() {
        if (!GameConfig.getServerBoolean("use_enforce_job_level_range") || isGmJob()) {
            return getMaxClassLevel();
        }

        return GameConstants.getJobMaxLevel(getJob());
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

    public int getMiniGamePoints(MiniGameResult type, boolean omok) {
        if (omok) {
            return switch (type) {
                case WIN -> omokwins;
                case LOSS -> omoklosses;
                default -> omokties;
            };
        } else {
            return switch (type) {
                case WIN -> matchcardwins;
                case LOSS -> matchcardlosses;
                default -> matchcardties;
            };
        }
    }

    public int getMonsterBookCover() {
        return bookCover;
    }

    public void setGMLevel(int level) {
        this.gmLevel = Math.max(Math.min(level, 6), 0);
        whiteChat = gmLevel >= 4;   // thanks ozanrijen for suggesting default white chat
    }

    public void closePartySearchInteractions() {
        this.getWorldServer().getPartySearchCoordinator().unregisterPartyLeader(this);
        if (canRecvPartySearchInvite) {
            this.getWorldServer().getPartySearchCoordinator().detachPlayer(this);
        }
    }

    public void closePlayerInteractions() {
        closeNpcShop();
        closeTrade();
        market.closePlayerShop();
        closeMiniGame(true);
        closeRPS();
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

    public void closeMiniGame(boolean forceClose) {
        MiniGame game = this.getMiniGame();
        if (game == null) {
            return;
        }

        if (game.isOwner(this)) {
            game.closeRoom(forceClose);
        } else {
            game.removeVisitor(forceClose, this);
        }
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

    public BuffEffectData getStatForBuff(EffectType effect) {
        // effLock/chrLock 已冗余：激活表为不可变快照，无锁读

        try {
            EffectStatus mbsvh = buffs.getActive().effects.get(effect);
            if (mbsvh == null) {
                return null;
            }
            return mbsvh.getData();
        } finally {

        }
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

    public int gmLevel() {
        return gmLevel;
    }

    public void handleEnergyChargeGain() { // to get here energychargelevel has to be > 0
        Skill energycharge = isCygnus() ? SkillFactory.getSkill(ThunderBreaker.ENERGY_CHARGE) : SkillFactory.getSkill(Marauder.ENERGY_CHARGE);
        BuffEffectData ceffect;
        ceffect = energycharge.getEffect(getSkillLevel(energycharge));
        TimerManager tMan = TimerManager.getInstance();
        if (energyBar < 10000) {
            energyBar += 102;
            if (energyBar > 10000) {
                energyBar = 10000;
            }
            List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.ENERGY_CHARGE, energyBar));
            setBuffedValue(EffectType.ENERGY_CHARGE, energyBar);
            sendPacket(PacketCreator.giveBuff(energyBar, 0, stat));
            sendPacket(PacketCreator.showOwnBuffEffect(energycharge.getId(), 2));
            getMap().broadcastPacket(this, PacketCreator.showBuffEffect(id, energycharge.getId(), 2));
            getMap().broadcastPacket(this, PacketCreator.giveForeignPirateBuff(id, energycharge.getId(),
                    ceffect.getDuration(), stat));
        }
        if (energyBar >= 10000 && energyBar < 11000) {
            energyBar = 15000;
            final Character chr = this;
            tMan.schedule(() -> {
                energyBar = 0;
                List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.ENERGY_CHARGE, energyBar));
                setBuffedValue(EffectType.ENERGY_CHARGE, energyBar);
                sendPacket(PacketCreator.giveBuff(energyBar, 0, stat));
                getMap().broadcastPacket(chr, PacketCreator.cancelForeignFirstDebuff(id, ((long) 1) << 50));
            }, ceffect.getDuration());
        }
    }

    public void handleOrbconsume() {
        int skillid = isCygnus() ? DawnWarrior.COMBO : Crusader.COMBO;
        Skill combo = SkillFactory.getSkill(skillid);
        List<Pair<EffectType, Integer>> stat = Collections.singletonList(new Pair<>(EffectType.COMBO, 1));
        setBuffedValue(EffectType.COMBO, 1);
        sendPacket(PacketCreator.giveBuff(skillid, combo.getEffect(getSkillLevel(combo)).getDuration() + (int) ((getBuffedStarttime(EffectType.COMBO) - System.currentTimeMillis())), stat));
        getMap().broadcastMessage(this, PacketCreator.giveForeignBuff(getId(), stat), false);
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

    public void hasGivenFame(Character to) {
        lastfametime = System.currentTimeMillis();
        lastmonthfameids.add(to.getId());
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO famelog (characterid, characterid_to) VALUES (?, ?)")) {
            ps.setInt(1, getId());
            ps.setInt(2, to.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean isGM() {
        return gmLevel > 1;
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

    int getChangedJobSp(Job newJob) {    // 包内可见：CharacterJob.changeJob 调用
        int curSp = getUsedSp(newJob) + getJobRemainingSp(newJob);
        int spGain = 0;
        int expectedSp = getJobLevelSp(level - 10, newJob, GameConstants.getJobBranch(newJob));
        if (curSp < expectedSp) {
            spGain += (expectedSp - curSp);
        }

        return getSpGain(spGain, curSp, newJob);
    }

    private int getUsedSp(Job job) {
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

    private int getJobLevelSp(int level, Job job, int jobBranch) {
        if (Job.getJobStyleInternal(job.getId(), (byte) 0x40) == Job.MAGICIAN) {
            level += 2;  // starts earlier, level 8
        }

        return 3 * level + GameConstants.getChangeJobSpUpgrade(jobBranch);
    }

    private int getJobMaxSp(Job job) {
        int jobBranch = GameConstants.getJobBranch(getJob());
        int jobRange = GameConstants.getJobUpgradeLevelRange(jobBranch);
        return getJobLevelSp(jobRange, job, jobBranch);
    }

    private int getJobRemainingSp(Job job) {
        return getRemainingSp(job.getId());
    }

    private int getSpGain(int spGain, Job job) {
        int curSp = getUsedSp(job) + getJobRemainingSp(job);
        return getSpGain(spGain, curSp, job);
    }

    private int getSpGain(int spGain, int curSp, Job job) {
        int maxSp = getJobMaxSp(job);
        return Math.min(spGain, maxSp - curSp);
    }

    private void levelUpGainSp() {
        if (GameConstants.getJobBranch(getJob()) == 0) {
            return;
        }

        int spGain = GameConfig.getServerInt("level_up_sp_gain");
        if (GameConfig.getServerBoolean("use_enforce_job_sp_range") && !GameConstants.hasSPTable(getJob())) {
            spGain = getSpGain(spGain, getJob());
        }

        if (spGain > 0) {
            gainSp(spGain, job.getId(), true);
        }
    }

    // getHpMpGainFromRange 和 getBasicLevelUpHpMp 已迁移到 CharacterStats

    public synchronized void levelUp(boolean takeexp) {
        Skill improvingMaxHP = null;
        Skill improvingMaxMP = null;
        int improvingMaxHPLevel = 0;
        int improvingMaxMPLevel = 0;

        boolean isBeginner = isBeginnerJob();
        if (GameConfig.getServerBoolean("use_auto_assign_starters_ap") && isBeginner && level < 11) {
        // effLock 已冗余：gainAp/assignStrDexIntLuk 只需 stats.wLock
            stats.wLock.lock();
            try {
                gainAp(5, true);

                int str = 0, dex = 0;
                if (level < 6) {
                    str += 5;
                } else {
                    str += 4;
                    dex += 1;
                }

                assignStrDexIntLuk(str, dex, 0, 0);
            } finally {
                stats.wLock.unlock();

            }
        } else {
            int remainingAp = GameConfig.getServerInt("level_up_ap_gain");

            if (isCygnus()) {
                if (level > 10) {
                    if (level <= 17) {
                        remainingAp += 2;
                    } else if (level < 77) {
                        remainingAp++;
                    }
                }
            }

            gainAp(remainingAp, true);
        }

        int addhp, addmp;
        Pair<Integer, Integer> basicHpMp = stats.getBasicLevelUpHpMp(getJob());
        addhp = basicHpMp.getLeft();
        addmp = basicHpMp.getRight();

        // 技能加成（Improving MaxHP/MaxMP）仍按原逻辑计算
        if (job.isA(Job.WARRIOR) || job.isA(Job.DAWNWARRIOR1)) {
            improvingMaxHP = isCygnus() ? SkillFactory.getSkill(DawnWarrior.MAX_HP_INCREASE) : SkillFactory.getSkill(Warrior.IMPROVED_MAXHP);
            if (job.isA(Job.CRUSADER)) {
                improvingMaxMP = SkillFactory.getSkill(1210000);
            } else if (job.isA(Job.DAWNWARRIOR2)) {
                improvingMaxMP = SkillFactory.getSkill(11110000);
            }
            improvingMaxHPLevel = getSkillLevel(improvingMaxHP);
        } else if (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1)) {
            improvingMaxMP = isCygnus() ? SkillFactory.getSkill(BlazeWizard.INCREASING_MAX_MP) : SkillFactory.getSkill(Magician.IMPROVED_MAX_MP_INCREASE);
            improvingMaxMPLevel = getSkillLevel(improvingMaxMP);
        } else if (job.isA(Job.PIRATE) || job.isA(Job.THUNDERBREAKER1)) {
            improvingMaxHP = isCygnus() ? SkillFactory.getSkill(ThunderBreaker.IMPROVE_MAX_HP) : SkillFactory.getSkill(Brawler.IMPROVE_MAX_HP);
            improvingMaxHPLevel = getSkillLevel(improvingMaxHP);
        }
        if (improvingMaxHPLevel > 0 && (job.isA(Job.WARRIOR) || job.isA(Job.PIRATE) || job.isA(Job.DAWNWARRIOR1) || job.isA(Job.THUNDERBREAKER1))) {
            addhp += improvingMaxHP.getEffect(improvingMaxHPLevel).getX();
        }
        if (improvingMaxMPLevel > 0 && (job.isA(Job.MAGICIAN) || job.isA(Job.CRUSADER) || job.isA(Job.BLAZEWIZARD1))) {
            addmp += improvingMaxMP.getEffect(improvingMaxMPLevel).getX();
        }

        if (GameConfig.getServerBoolean("use_randomize_hpmp_gain")) {
            if (getJobStyle() == Job.MAGICIAN) {
                addmp += stats.localAttrs[INT] / 20;
            } else {
                addmp += stats.localAttrs[INT] / 10;
            }
        }

        addMaxMPMaxHP(addhp, addmp, true);

        if (takeexp) {
            exp.addAndGet(-ExpTable.getExpNeededForLevel(level));
            if (exp.get() < 0) {
                exp.set(0);
            }
        }

        level++;
        if (level >= getMaxClassLevel()) {
            exp.set(0);

            int maxClassLevel = getMaxClassLevel();
            if (level == maxClassLevel) {
                if (!this.isGM()) {
                    if (GameConfig.getServerBoolean("playernpc_auto_deploy")) {
                        ThreadManager.getInstance().newTask(() -> PlayerNPC.spawnPlayerNPC(GameConstants.getHallOfFameMapid(getJob()), Character.this));
                    }

                    final String names = (getMedalText() + name);
                    getWorldServer().broadcastPacket(PacketCreator.serverNotice(6, String.format(ServerConstants.LEVEL_200, names, maxClassLevel, names)));
                }
            }

            level = maxClassLevel; //To prevent levels past the maximum
        }

        levelUpGainSp();

        // effLock 已冗余：recalcLocalStats/changeHpMp 只需 stats.wLock
        stats.wLock.lock();
        try {
            recalcLocalStats();
            changeHpMp(stats.localMaxHp, stats.localMaxMp, true);

            List<Pair<Stat, Integer>> statup = new ArrayList<>(10);
            statup.add(new Pair<>(Stat.AVAILABLEAP, ap.remainingAp));
            statup.add(new Pair<>(Stat.AVAILABLESP, sp.remainingSp[CharacterSp.indexOf(job.getId())]));
            statup.add(new Pair<>(Stat.HP, stats.hp));
            statup.add(new Pair<>(Stat.MP, stats.mp));
            statup.add(new Pair<>(Stat.EXP, exp.get()));
            statup.add(new Pair<>(Stat.LEVEL, level));
            statup.add(new Pair<>(Stat.MAXHP, stats.clientMaxHp));
            statup.add(new Pair<>(Stat.MAXMP, stats.clientMaxMp));
            statup.add(new Pair<>(Stat.STR, stats.attrs[STR]));
            statup.add(new Pair<>(Stat.DEX, stats.attrs[DEX]));

            sendPacket(PacketCreator.updatePlayerStats(statup, true, this));
        } finally {
            stats.wLock.unlock();

        }

        getMap().broadcastMessage(this, PacketCreator.showForeignEffect(getId(), 0), false);
        setMPC(new PartyCharacter(this));
        silentPartyUpdate();

        if (guild.getGuildId() > 0) {
            getGuild().broadcast(PacketCreator.levelUpMessage(2, level, name), this.getId());
        }

        if (level % 20 == 0) {
            if (GameConfig.getServerBoolean("use_add_slots_by_level")) {
                if (!isGM()) {
                    for (byte i = 1; i < 5; i++) {
                        gainSlots(i, 4, true);
                    }

                    this.yellowMessage(I18nUtil.getMessage("Character.levelUp.USE_ADD_SLOTS_BY_LEVEL", level));
                }
            }
            if (GameConfig.getServerBoolean("use_add_rates_by_level")) { //For the rate upgrade
                revertLastPlayerRates();
                setPlayerRates();
                this.yellowMessage(I18nUtil.getMessage("Character.levelUp.USE_ADD_RATES_BY_LEVEL", level));
            }
        }

        if (GameConfig.getServerBoolean("use_perfect_pitch") && level >= 30) {
            //milestones?
            if (InventoryManipulator.checkSpace(client, ItemId.PERFECT_PITCH, (short) 1, "")) {
                InventoryManipulator.addById(client, ItemId.PERFECT_PITCH, (short) 1, "", -1);
            }
        } else if (level == 10) {
            ThreadManager.getInstance().newTask(() -> {
                if (leaveParty()) {
                    showHint(I18nUtil.getMessage("Character.levelUp.LeaveStarterParty"));
                }
            });
        }

        guild.guildUpdate();

        FamilyEntry familyEntry = getFamilyEntry();
        if (familyEntry != null) {
            familyEntry.giveReputationToSenior(GameConfig.getServerInt("family_rep_per_level_up"), true);
            FamilyEntry senior = familyEntry.getSenior();
            if (senior != null) { //only send the message to direct senior
                Character seniorChr = senior.getChr();
                if (seniorChr != null) {
                    seniorChr.sendPacket(PacketCreator.levelUpMessage(1, level, getName()));
                }
            }
        }

        updateMobExpRate();
    }

    public void addPlayerRing(Ring ring) {
        int ringItemId = ring.getItemId();
        if (ItemId.isWeddingRing(ringItemId)) {
            this.marriageRing = ring;
        } else if (ring.getItemId() > 1112012) {
            synchronized (friendshipRings) {
                this.friendshipRings.add(ring);
            }
        } else {
            synchronized (crushRings) {
                this.crushRings.add(ring);
            }
        }
    }

    public static Character loadCharacterEntryFromDB(ResultSet rs, List<Item> equipped) {
        Character ret = new Character();

        try {
            ret.accountId = rs.getInt("accountid");
            ret.id = rs.getInt("id");
            ret.name = rs.getString("name");
            ret.gender = rs.getInt("gender");
            ret.skinColor = SkinColor.getById(rs.getInt("skincolor"));
            ret.face = rs.getInt("face");
            ret.hair = rs.getInt("hair");

            // skipping pets, probably unneeded here

            ret.level = rs.getInt("level");
            // job 仅从 character_json 恢复（applyData），character 表 job 列为冗余双写
            ret.applyData(CharacterData.deserialize(rs.getString("stats_json")));
            ret.exp.set(rs.getInt("exp"));
            ret.fame = rs.getInt("fame");
            ret.gachaExp.set(rs.getInt("gachaexp"));
            // mapId 仅从 character_json 恢复（applyData），character 表 map 列为冗余双写
            ret.initialSpawnPoint = rs.getInt("spawnpoint");
            ret.setGMLevel(rs.getInt("gm"));
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
        ret.skinColor = this.getSkinColor();
        ret.face = this.getFace();
        ret.hair = this.getHair();

        // skipping pets, probably unneeded here

        ret.level = this.getLevel();
        ret.setJob(this.getJob());
        for (int i = 0; i < BASE_STAT_COUNT; i++) {
            ret.stats.attrs[i] = this.stats.getAttr(i);
        }
        ret.stats.hp = this.getHp();
        ret.stats.setMaxHp(this.getMaxHp());
        ret.stats.mp = this.getMp();
        ret.stats.setMaxMp(this.getMaxMp());
        ret.ap.remainingAp = this.getRemainingAp();
        ret.setRemainingSp(this.getRemainingSps());
        ret.exp.set(this.getExp());
        ret.fame = this.getFame();
        ret.gachaExp.set(this.getGachaExp());
        ret.setMapId(this.getMapId());
        ret.initialSpawnPoint = this.getInitialSpawnPoint();

        ret.inventory.inventories[InventoryType.EQUIPPED.ordinal()] = this.getInventory(InventoryType.EQUIPPED);

        ret.setGMLevel(this.gmLevel());
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
        chr.setLevel(charactersDO.getLevel());
        chr.setFame(charactersDO.getFame());
        chr.quests.setQuestFame(charactersDO.getFquest());
        loadDataFromJson(chr, charactersDO.getId());
        chr.setExp(charactersDO.getExp());
        chr.setGachaExp(charactersDO.getGachaexp());
        chr.setHasMerchant(charactersDO.getHasmerchant());
        chr.setMeso(charactersDO.getMeso());
        chr.setMerchantMeso(charactersDO.getMerchantmesos());
        chr.setGMLevel(charactersDO.getGm());
        chr.setSkinColor(SkinColor.getById(charactersDO.getSkincolor()));
        chr.setGender(charactersDO.getGender());
        // job 仅从 character_json 恢复（applyData），character 表 job 列为冗余双写
        chr.setFinishedDojoTutorial(charactersDO.getFinishedDojoTutorial() == 1);
        chr.setVanquisherKills(charactersDO.getVanquisherKills());
        chr.setOmokwins(charactersDO.getOmokwins());
        chr.setOmoklosses(charactersDO.getOmoklosses());
        chr.setOmokties(charactersDO.getOmokties());
        chr.setMatchcardwins(charactersDO.getMatchcardwins());
        chr.setMatchcardlosses(charactersDO.getMatchcardlosses());
        chr.setMatchcardties(charactersDO.getMatchcardties());
        chr.setHair(charactersDO.getHair());
        chr.setFace(charactersDO.getFace());
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
        chr.setFamilyId(charactersDO.getFamilyId());
        chr.setBookCover(charactersDO.getMonsterbookcover());
        chr.setMonsterBook(new MonsterBook(charactersDO.getId()));
        chr.setVanquisherStage(charactersDO.getVanquisherStage());
        chr.pq.setAriantPoints(charactersDO.getAriantPoints());
        chr.setDojoPoints(charactersDO.getDojoPoints());
        chr.setDojoStage(charactersDO.getLastDojoStage());
        chr.pq.setDataString(charactersDO.getDataString());
        chr.guild.setMGC(new GuildCharacter(chr));
        chr.setBuddylist(new BuddyList(charactersDO.getBuddyCapacity()));
        chr.setLastExpGainTime(charactersDO.getLastExpGainTime().getTime());
        chr.setCanRecvPartySearchInvite(charactersDO.getPartySearch());
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
        chr.setPartnerId(charactersDO.getPartnerId());
        chr.setMarriageItemId(charactersDO.getMarriageItemId());
        World world = Server.getInstance().getWorld(charactersDO.getWorld());
        if (charactersDO.getMarriageItemId() > 0 && charactersDO.getPartnerId() <= 0) {
            chr.setMarriageItemId(-1);
        } else if (charactersDO.getPartnerId() > 0 && world.getRelationshipId(charactersDO.getId()) <= 0) {
            chr.setMarriageItemId(-1);
            chr.setPartnerId(-1);
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
            chr.setQuickSlotLoaded(NumberTool.LongToBytes(quickSlotKeyMap.getKeymap()));
            chr.setQuickSlotKeyMapped(new QuickslotBinding(chr.getQuickSlotLoaded()));
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
        job.setJob(Job.getById(data.jobId));
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

    void playerDead() {    // 包内可见：CharacterStats.hpChangeAction 调用
        if (this.getMap().isCPQMap()) {
            int losing = getMap().getDeathCP();
            if (pq.getCP() < losing) {
                losing = pq.getCP();
            }
            getMap().broadcastMessage(PacketCreator.playerDiedMessage(getName(), losing, pq.getTeam()));
            pq.gainCP(-losing);
            return;
        }

        cancelAllBuffs(false);
        dispelDebuffs();

        EventInstanceManager eim = getEventInstance();
        if (eim != null) {
            eim.playerKilled(this);
        }
        int[] charmID = {ItemId.SAFETY_CHARM, ItemId.EASTER_BASKET, ItemId.EASTER_CHARM};
        int possesed = 0;
        int i;
        for (i = 0; i < charmID.length; i++) {
            int quantity = getItemQuantity(charmID[i], false);
            if (quantity > 0) {
                possesed = quantity;
                break;
            }
        }
        usedSafetyCharm = false;
        if (possesed > 0 && !MapId.isDojo(getMapId())) {
            message(I18nUtil.getMessage("Character.useItem.message1"));  //使用安全护符，不扣经验
            InventoryManipulator.removeById(client, ItemConstants.getInventoryType(charmID[i]), charmID[i], 1, true, false);
            usedSafetyCharm = true;
        } else if (getJob() != Job.BEGINNER) { //Hmm...
            if (!FieldLimit.NO_EXP_DECREASE.check(getMap().getFieldLimit())) {  // thanks Conrad for noticing missing FieldLimit check
                int XPdummy = ExpTable.getExpNeededForLevel(getLevel());

                if (getMap().isTown()) {    // thanks MindLove, SIayerMonkey, HaItsNotOver for noting players only lose 1% on town maps
                    XPdummy /= 100;
                } else {
                    if (getLuk() < 50) {    // thanks Taiketo, Quit, Fishanelli for noting player EXP loss are fixed, 50-LUK threshold
                        XPdummy /= 10;
                    } else {
                        XPdummy /= 20;
                    }
                }

                int curExp = getExp();
                if (curExp > XPdummy) {
                    loseExp(XPdummy, false, false);
                } else {
                    loseExp(curExp, false, false);
                }
            }
        }

        if (getBuffedValue(EffectType.MORPH) != null) {
            cancelEffectFromBuffStat(EffectType.MORPH);
        }

        if (getBuffedValue(EffectType.MONSTER_RIDING) != null) {
            cancelEffectFromBuffStat(EffectType.MONSTER_RIDING);
        }

        chair.unsitChairInternal();
        enableActions();
    }

    public void respawn(int returnMap) {
        respawn(null, returnMap);    // unspecified EIM, don't force EIM unregister in this case
    }

    public void respawn(EventInstanceManager eim, int returnMap) {
        if (eim != null) {
            eim.unregisterPlayer(this);    // some event scripts uses this...
        }
        changeMap(returnMap);

        cancelAllBuffs(false);  // thanks Oblivium91 for finding out players still could revive in area and take damage before returning to town

        if (usedSafetyCharm) {  // thanks kvmba for noticing safety charm not providing 30% HP/MP
            addMPHP((int) Math.ceil(stats.clientMaxHp * 0.3), (int) Math.ceil(stats.clientMaxMp * 0.3));
        } else {
            updateHp(50);
        }

        setStance(0);
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

    public void receivePartyMemberHP() {
        // 不在此处包一层 party.prtLock:getPartyMembersOnSameMap 内部已持 party.prtLock 保护 party 引用。
        // 若再包一层,会在持 party.prtLock 的同时对同图队友逐个取 getHp()(对方 stats.rLock),
        // 与 updateLocalStats(本角色 stats.wLock 后再 updatePartyMemberHP 取 party.prtLock)形成
        // 跨角色反向锁顺序,存在死锁窗口。
        for (Character partychar : this.getPartyMembersOnSameMap()) {
            sendPacket(PacketCreator.updatePartyMemberHP(partychar.getId(), partychar.getHp(), partychar.getCurrentMaxHp()));
        }
    }

    public void removeVisibleMapObject(MapObject mo) {
        visibleMapObjects.remove(mo);
    }

    public synchronized void resetStats() {
        if (!GameConfig.getServerBoolean("use_auto_assign_starters_ap")) {
            return;
        }

        // effLock 已冗余：reset 仅动 stats/ap + applyUpdateSilently
        stats.wLock.lock();
        try {
            int tap = ap.remainingAp + stats.attrs[STR] + stats.attrs[DEX] + stats.attrs[INT] + stats.attrs[LUK], tsp = 1;
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
                Map<Stat, Integer> statUpdates = stats.applyUpdateSilently(new StatsUpdate()
                        .setAttr(STR, tstr).setAttr(DEX, tdex).setAttr(INT, tint).setAttr(LUK, tluk).setAp(tap));
                statUpdates.put(Stat.AVAILABLESP, sp.changeRemainingSp(tsp, job.getId(), true));
                stats.announceStatsUpdate(statUpdates);
            } else {
                log.warn("Chr {} tried to have its stats reset without enough AP available", getName());
            }
        } finally {
            stats.wLock.unlock();

        }
    }

    public void resetBattleshipHp() {
        int bshipLevel = Math.max(getLevel() - 120, 0);  // thanks alex12 for noticing battleship HP issues for low-level players
        this.battleshipHp = 400 * getSkillLevel(SkillFactory.getSkill(Corsair.BATTLE_SHIP)) + (bshipLevel * 200);
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
        stats.attrs[STR] = recipe.getStr();
        stats.attrs[DEX] = recipe.getDex();
        stats.attrs[INT] = recipe.getInt();
        stats.attrs[LUK] = recipe.getLuk();
        stats.setMaxHp(recipe.getMaxHp());
        stats.setMaxMp(recipe.getMaxMp());
        stats.hp = stats.maxHp;
        stats.mp = stats.maxMp;
        level = recipe.getLevel();
        ap.remainingAp = recipe.getRemainingAp();
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
                    ps.setInt(1, gmLevel);
                    ps.setInt(2, skinColor.getId());
                    ps.setInt(3, gender);
                    ps.setInt(4, job.getId());
                    ps.setInt(5, hair);
                    ps.setInt(6, face);
                    ps.setInt(7, Math.abs(meso.get()));
                    ps.setInt(8, 0);
                    ps.setInt(9, accountId);
                    ps.setString(10, name);
                    ps.setInt(11, world);
                    ps.setInt(12, level);

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
                boolean bQuickslotEquals = this.quickSlotKeyMapped == null || (this.quickSlotLoaded != null && Arrays.equals(this.quickSlotKeyMapped.GetKeybindings(), this.quickSlotLoaded));
                if (!bQuickslotEquals) {
                    long nQuickslotKeymapped = NumberTool.BytesToLong(this.quickSlotKeyMapped.GetKeybindings());

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
            log.error("Error creating chr {}, level: {}, job: {}", name, level, job.getId(), t);
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
                    ps.setInt(1, level);    // thanks CanIGetaPR for noticing an unnecessary "level" limitation when persisting DB data
                    ps.setInt(2, fame);

                    stats.wLock.lock();   // effLock 已移除：仅序列化 stats + 读原子字段
                    try {
                        statsJson = toData().serialize();

                        ps.setInt(3, Math.abs(exp.get()));
                        ps.setInt(4, Math.abs(gachaExp.get()));
                    } finally {
                        stats.wLock.unlock();
                    }

                    ps.setInt(5, gmLevel);
                    ps.setInt(6, skinColor.getId());
                    ps.setInt(7, gender);
                    ps.setInt(8, job.getId());
                    ps.setInt(9, hair);
                    ps.setInt(10, face);
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

                    party.lock.lock();
                    try {
                        if (party.party != null) {
                            ps.setInt(13, party.party.getId());
                        } else {
                            ps.setInt(13, -1);
                        }
                    } finally {
                        party.lock.unlock();
                    }

                    ps.setInt(14, buddylist.getCapacity());
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
                    ps.setInt(30, matchcardwins);
                    ps.setInt(31, matchcardlosses);
                    ps.setInt(32, matchcardties);
                    ps.setInt(33, omokwins);
                    ps.setInt(34, omoklosses);
                    ps.setInt(35, omokties);
                    ps.setString(36, pq.getDataString());
                    ps.setInt(37, quests.getQuestFame());
                    ps.setInt(38, partnerId);
                    ps.setInt(39, marriageItemId);
                    ps.setTimestamp(40, new Timestamp(lastExpGainTime));
                    ps.setInt(41, pq.getAriantPoints());
                    ps.setBoolean(42, canRecvPartySearchInvite);
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

                    Set<Entry<Integer, KeyBinding>> keybindingItems = Collections.unmodifiableSet(keymap.entrySet());
                    for (Entry<Integer, KeyBinding> keybinding : keybindingItems) {
                        psKey.setInt(2, keybinding.getKey());
                        psKey.setInt(3, keybinding.getValue().getType());
                        psKey.setInt(4, keybinding.getValue().getAction());
                        psKey.addBatch();
                    }
                    psKey.executeBatch();
                }

                // No quickslots, or no change.
                boolean bQuickslotEquals = this.quickSlotKeyMapped == null || (this.quickSlotLoaded != null && Arrays.equals(this.quickSlotKeyMapped.GetKeybindings(), this.quickSlotLoaded));
                if (!bQuickslotEquals) {
                    long nQuickslotKeymapped = NumberTool.BytesToLong(this.quickSlotKeyMapped.GetKeybindings());

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

                    for (BuddylistEntry entry : buddylist.getBuddies()) {
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

                FamilyEntry familyEntry = getFamilyEntry(); //save family rep
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

                if (storage != null && usedStorage) {
                    storage.saveToDB(con);
                    usedStorage = false;
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
            log.error("Error saving chr {}, level: {}, job: {}", name, level, job.getId(), e);
        }
    }

    public void sendKeymap() {
        sendPacket(PacketCreator.getKeymap(keymap));
    }

    public void sendQuickmap() {
        // send quickslots to user
        QuickslotBinding pQuickslotKeyMapped = this.quickSlotKeyMapped;

        if (pQuickslotKeyMapped == null) {
            pQuickslotKeyMapped = new QuickslotBinding(QuickslotBinding.DEFAULT_QUICKSLOTS);
        }

        this.sendPacket(PacketCreator.QuickslotMappedInit(pQuickslotKeyMapped));
    }

    public void sendMacros() {
        // Always send the macro packet to fix a client side bug when switching characters.
        sendPacket(PacketCreator.getMacros(skillMacros));
    }

    public void setBuddyCapacity(int capacity) {
        buddylist.setCapacity(capacity);
        sendPacket(PacketCreator.updateBuddyCapacity(capacity));
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

    public void setExp(int amount) {
        this.exp.set(amount);
    }

    public void setGachaExp(int exp) {
        this.gachaExp.set(exp);
    }

    public void finishDojoTutorial() {
        this.finishedDojoTutorial = true;
    }

    public void setGM(int level) {
        this.gmLevel = level;
    }

    // ── 属性变更钩子：原 CharacterListener 的实现合并至此 ──

    /** HP/MP 池更新后的重算与钳制，返回需并入本次公告的属性修正 */

    // calcHpRatioUpdate / calcMpRatioUpdate / calcTransientRatio / calcHpRatioTransient / calcMpRatioTransient
    // 计算部分已迁移到 CharacterStats，以下是使用这些计算的编排方法

    public void setMiniGamePoints(Character visitor, int winnerslot, boolean omok) {
        if (omok) {
            if (winnerslot == 1) {
                this.omokwins++;
                visitor.omoklosses++;
            } else if (winnerslot == 2) {
                visitor.omokwins++;
                this.omoklosses++;
            } else {
                this.omokties++;
                visitor.omokties++;
            }
        } else {
            if (winnerslot == 1) {
                this.matchcardwins++;
                visitor.matchcardlosses++;
            } else if (winnerslot == 2) {
                visitor.matchcardwins++;
                this.matchcardlosses++;
            } else {
                this.matchcardties++;
                visitor.matchcardties++;
            }
        }
    }

    public void setRPS(RockPaperScissor rps) {
        this.rps = rps;
    }

    public void closeRPS() {
        RockPaperScissor rps = this.rps;
        if (rps != null) {
            rps.dispose(client);
            setRPS(null);
        }
    }

    private static boolean hasMergeFlag(Item item) {
        return (item.getFlag() & ItemConstants.MERGE_UNTRADEABLE) == ItemConstants.MERGE_UNTRADEABLE;
    }

    private static void setMergeFlag(Item item) {
        short flag = item.getFlag();
        flag |= ItemConstants.MERGE_UNTRADEABLE;
        flag |= ItemConstants.UNTRADEABLE;
        item.setFlag(flag);
    }

    private List<Equip> getUpgradeableEquipped() {
        List<Equip> list = new LinkedList<>();

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item item : getInventory(InventoryType.EQUIPPED)) {
            if (ii.isUpgradeable(item.getItemId())) {
                list.add((Equip) item);
            }
        }

        return list;
    }

    private static List<Equip> getEquipsWithStat(List<Pair<Equip, Map<StatUpgrade, Short>>> equipped, StatUpgrade stat) {
        List<Equip> equippedWithStat = new LinkedList<>();

        for (Pair<Equip, Map<StatUpgrade, Short>> eq : equipped) {
            if (eq.getRight().containsKey(stat)) {
                equippedWithStat.add(eq.getLeft());
            }
        }

        return equippedWithStat;
    }

    public boolean mergeAllItemsFromName(String name) {
        InventoryType type = InventoryType.EQUIP;

        Inventory inv = getInventory(type);
        inv.lockInventory();
        try {
            Item it = inv.findByName(name);
            if (it == null) {
                return false;
            }

            Map<StatUpgrade, Float> statups = new LinkedHashMap<>();
            mergeAllItemsFromPosition(statups, it.getPosition());

            List<Pair<Equip, Map<StatUpgrade, Short>>> upgradeableEquipped = new LinkedList<>();
            Map<Equip, List<Pair<StatUpgrade, Integer>>> equipUpgrades = new LinkedHashMap<>();
            for (Equip eq : getUpgradeableEquipped()) {
                upgradeableEquipped.add(new Pair<>(eq, eq.getStats()));
                equipUpgrades.put(eq, new LinkedList<Pair<StatUpgrade, Integer>>());
            }

            /*
            for (Entry<StatUpgrade, Float> es : statups.entrySet()) {
                System.out.println(es);
            }
            */

            for (Entry<StatUpgrade, Float> e : statups.entrySet()) {
                Double ev = Math.sqrt(e.getValue());

                Set<Equip> extraEquipped = new LinkedHashSet<>(equipUpgrades.keySet());
                List<Equip> statEquipped = getEquipsWithStat(upgradeableEquipped, e.getKey());
                float extraRate = (float) (0.2 * Math.random());

                if (!statEquipped.isEmpty()) {
                    float statRate = 1.0f - extraRate;

                    int statup = (int) Math.ceil((ev * statRate) / statEquipped.size());
                    for (Equip statEq : statEquipped) {
                        equipUpgrades.get(statEq).add(new Pair<>(e.getKey(), statup));
                        extraEquipped.remove(statEq);
                    }
                }

                if (!extraEquipped.isEmpty()) {
                    int statup = (int) Math.round((ev * extraRate) / extraEquipped.size());
                    if (statup > 0) {
                        for (Equip extraEq : extraEquipped) {
                            equipUpgrades.get(extraEq).add(new Pair<>(e.getKey(), statup));
                        }
                    }
                }
            }

            dropMessage(6, "EQUIPMENT MERGE operation results:");
            for (Entry<Equip, List<Pair<StatUpgrade, Integer>>> eqpUpg : equipUpgrades.entrySet()) {
                List<Pair<StatUpgrade, Integer>> eqpStatups = eqpUpg.getValue();
                if (!eqpStatups.isEmpty()) {
                    Equip eqp = eqpUpg.getKey();
                    setMergeFlag(eqp);

                    String showStr = " '" + ItemInformationProvider.getInstance().getName(eqp.getItemId()) + "': ";
                    String upgdStr = eqp.gainStats(eqpStatups).getLeft();

                    this.forceUpdateItem(eqp);

                    showStr += upgdStr;
                    dropMessage(6, showStr);
                }
            }

            return true;
        } finally {
            inv.unlockInventory();
        }
    }

    public void mergeAllItemsFromPosition(Map<StatUpgrade, Float> statUps, short pos) {
        Inventory inv = getInventory(InventoryType.EQUIP);
        inv.lockInventory();
        try {
            for (short i = pos; i <= inv.getSlotLimit(); i++) {
                standaloneMerge(statUps, getClient(), InventoryType.EQUIP, i, inv.getItem(i));
            }
        } finally {
            inv.unlockInventory();
        }
    }

    private void standaloneMerge(Map<StatUpgrade, Float> statUps, Client c, InventoryType type, short slot, Item item) {
        short quantity;
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (item == null || (quantity = item.getQuantity()) < 1 || ii.isCash(item.getItemId()) || !ii.isUpgradeable(item.getItemId()) || hasMergeFlag(item)) {
            return;
        }

        Equip e = (Equip) item;
        for (Entry<StatUpgrade, Short> s : e.getStats().entrySet()) {
            Float newVal = statUps.get(s.getKey());

            float incVal = s.getValue().floatValue();
            incVal = switch (s.getKey()) {
                case incPAD, incMAD, incPDD, incMDD -> (float) Math.log(incVal);
                default -> incVal;
            };

            if (newVal != null) {
                newVal += incVal;
            } else {
                newVal = incVal;
            }

            statUps.put(s.getKey(), newVal);
        }

        InventoryManipulator.removeFromSlot(c, type, (byte) slot, quantity, false);
    }

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

    public void updateSingleStat(Stat stat, int newval) {
        updateSingleStat(stat, newval, false);
    }

    private void updateSingleStat(Stat stat, int newval, boolean itemReaction) {
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

    public void equippedItem(Equip equip) {
        int itemid = equip.getItemId();

        if (itemid == ItemId.PENDANT_OF_THE_SPIRIT) {
            this.equipPendantOfSpirit();
        }
    }

    public void unequippedItem(Equip equip) {
        int itemid = equip.getItemId();

        if (itemid == ItemId.PENDANT_OF_THE_SPIRIT) {
            this.unequipPendantOfSpirit();
        }
    }

    private void equipPendantOfSpirit() {   //精灵吊坠装备时长经验计算
        if (pendantOfSpirit == null) {
            pendantOfSpirit = TimerManager.getInstance().register(() -> {
                if (pendantExp < 3) {
                    pendantExp++;
                    //用于准确提示装备1小时内还是装备经过几小时
                    message(I18nUtil.getMessage(pendantExp <= 2 ? "Character.equipPendantOfSpirit.message1" : "Character.equipPendantOfSpirit.message2", pendantExp == 3 ? 2 : pendantExp, pendantExp * 10));
                } else {
                    pendantOfSpirit.cancel(false);
                }
            }, 3600000); //1 hour
        }
    }

    private void unequipPendantOfSpirit() {
        if (pendantOfSpirit != null) {
            pendantOfSpirit.cancel(false);
            pendantOfSpirit = null;
        }
        pendantExp = 0;
    }

    private Collection<Item> getUpgradeableEquipList() {
        Collection<Item> fullList = getInventory(InventoryType.EQUIPPED).list();
        if (GameConfig.getServerBoolean("use_equipment_level_up_cash")) {
            return fullList;
        }

        Collection<Item> eqpList = new LinkedHashSet<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item it : fullList) {
            if (!ii.isCash(it.getItemId())) {
                eqpList.add(it);
            }
        }

        return eqpList;
    }

    public void increaseEquipExp(int expGain) {
        if (allowExpGain) {     // thanks Vcoc for suggesting equip EXP gain conditionally
            if (expGain < 0) {
                expGain = Integer.MAX_VALUE;
            }

            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            for (Item item : getUpgradeableEquipList()) {
                Equip nEquip = (Equip) item;
                String itemName = ii.getName(nEquip.getItemId());
                if (itemName == null) {
                    continue;
                }

                nEquip.gainItemExp(client, expGain);
            }
        }
    }

    public void showAllEquipFeatures() {
        StringBuilder showMsg = new StringBuilder();

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item item : getInventory(InventoryType.EQUIPPED).list()) {
            Equip nEquip = (Equip) item;
            String itemName = ii.getName(nEquip.getItemId());
            if (itemName == null) {
                continue;
            }

            showMsg.append(nEquip.showEquipFeatures(client));
        }

        if (!showMsg.isEmpty()) {
            this.showHint("#ePLAYER EQUIPMENTS:#n\r\n\r\n" + showMsg, 400);
        }
    }

    public void broadcastMarriageMessage() {
        Guild guild = this.getGuild();
        if (guild != null) {
            guild.broadcast(PacketCreator.marriageMessage(0, name));
        }

        Family family = this.getFamily();
        if (family != null) {
            family.broadcast(PacketCreator.marriageMessage(1, name));
        }
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

        if (pendantOfSpirit != null) {
            pendantOfSpirit.cancel(true);
        }
        pendantOfSpirit = null;

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
            FamilyEntry familyEntry = getFamilyEntry();
            if (familyEntry != null) {
                familyEntry.setCharacter(null);
                setFamilyEntry(null);
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

    public boolean getWhiteChat() {
        return isGM() && whiteChat;
    }

    public void toggleWhiteChat() {
        whiteChat = !whiteChat;
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

    public void setReborns(int value) {
        if (!GameConfig.getServerBoolean("use_rebirth_system")) {
            yellowMessage(I18nUtil.getMessage("Character.USE_REBIRTH_SYSTEM")); //重生系统未启用
            throw new NotEnabledException();
        }

        characterService.update(CharactersDO.builder()
                .id(id)
                .reborns(value)
                .build());
    }

    public void addReborns() {
        setReborns(getReborns() + 1);
    }

    public int getReborns() {
        if (!GameConfig.getServerBoolean("use_rebirth_system")) {
            yellowMessage(I18nUtil.getMessage("Character.USE_REBIRTH_SYSTEM")); //重生系统未启用
            throw new NotEnabledException();
        }

        CharactersDO charactersDO = characterService.findById(id);
        return charactersDO == null ? 0 : Optional.ofNullable(charactersDO.getReborns()).orElse(0);
    }

    public void executeRebornAsId(int jobId) {
        executeRebornAs(Job.getById(jobId));
    }

    public void executeRebornAs(Job job) {
        if (!GameConfig.getServerBoolean("use_rebirth_system")) {
            yellowMessage(I18nUtil.getMessage("Character.USE_REBIRTH_SYSTEM")); //重生系统未启用
            throw new NotEnabledException();
        }
        if (getLevel() != getMaxClassLevel()) {
            return;
        }
        addReborns();
        changeJob(job);
        setLevel(0);
        levelUp(true);
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

    /**
     * 发装备，除id外都可以传null，传null取装备默认属性
     *
     * @param itemId      装备id
     * @param attStr      力量
     * @param attDex      敏捷
     * @param attInt      智力
     * @param attLuk      运气
     * @param attHp       血量
     * @param attMp       蓝量
     * @param pAtk        物理攻击
     * @param mAtk        魔法攻击
     * @param pDef        物理防御
     * @param mDef        魔法防御
     * @param acc         命中
     * @param avoid       回避
     * @param hands       攻击速度
     * @param speed       移动速度
     * @param jump        跳跃
     * @param upgradeSlot 可升级次数
     * @param expireTime  失效时间，-1为不失效 来自 @leevccc 的建议，传值则为分钟
     */
    public void gainEquip(int itemId, Short attStr, Short attDex, Short attInt, Short attLuk, Short attHp, Short attMp,
                          Short pAtk, Short mAtk, Short pDef, Short mDef, Short acc, Short avoid, Short hands, Short speed,
                          Short jump, Byte upgradeSlot, Long expireTime) {
        if (!ItemConstants.getInventoryType(itemId).equals(InventoryType.EQUIP)) {
            message(I18nUtil.getMessage("AbstractPlayerInteraction.gainEquip.message1"));
            return;
        }
        Equip baseEquip = (Equip) ItemInformationProvider.getInstance().getEquipById(itemId);
        baseEquip.setQuantity((short) 1);
        if (!InventoryManipulator.checkSpace(getClient(), itemId, 1, baseEquip.getOwner())) {
            message(I18nUtil.getMessage("AbstractPlayerInteraction.gainEquip.message2", InventoryType.EQUIP.getName()));
        }
        RequireUtil.requireNotEmptyAndThen(baseEquip, attStr, Equip::setStr);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attDex, Equip::setDex);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attInt, Equip::setInt);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attLuk, Equip::setLuk);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attHp, Equip::setHp);
        RequireUtil.requireNotEmptyAndThen(baseEquip, attMp, Equip::setMp);
        RequireUtil.requireNotEmptyAndThen(baseEquip, pAtk, Equip::setWatk);
        RequireUtil.requireNotEmptyAndThen(baseEquip, mAtk, Equip::setMatk);
        RequireUtil.requireNotEmptyAndThen(baseEquip, pDef, Equip::setWdef);
        RequireUtil.requireNotEmptyAndThen(baseEquip, mDef, Equip::setMdef);
        RequireUtil.requireNotEmptyAndThen(baseEquip, acc, Equip::setAcc);
        RequireUtil.requireNotEmptyAndThen(baseEquip, avoid, Equip::setAvoid);
        RequireUtil.requireNotEmptyAndThen(baseEquip, hands, Equip::setHands);
        RequireUtil.requireNotEmptyAndThen(baseEquip, speed, Equip::setSpeed);
        RequireUtil.requireNotEmptyAndThen(baseEquip, jump, Equip::setJump);
        RequireUtil.requireNotEmptyAndThen(baseEquip, upgradeSlot, Equip::setUpgradeSlots);
        RequireUtil.requireNotEmptyAndThen(baseEquip, expireTime, (eq, ep) -> {
            if (ep > 0) {
                eq.setExpiration(TimeUnit.MINUTES.toMillis(ep) + System.currentTimeMillis());
            } else {
                eq.setExpiration(-1);
            }
        });
        InventoryManipulator.addFromDrop(getClient(), baseEquip, false);
    }

    public void setFamilyBuff(boolean type, float exp, float drop) {
        this.familyBuff = type;
        this.familyExp = exp;
        this.familyDrop = drop;
    }

    public void startFamilyBuffTimer(int delay) {
        if (FamilyBuffTimer != null && !FamilyBuffTimer.isCancelled()) {
            FamilyBuffTimer.cancel(false);
        }
        FamilyBuffTimer = TimerManager.getInstance().schedule(() -> {
            try {
                sendPacket(PacketCreator.cancelFamilyBuff());
            } finally {
                cancelFamilyBuffTimer();
            }
        }, delay);
    }

    public void cancelFamilyBuffTimer() {
        if (FamilyBuffTimer != null && !FamilyBuffTimer.isCancelled()) {
            FamilyBuffTimer.cancel(false);
            setFamilyBuff(false, 1, 1);
        }
    }

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

    public Job getJobStyle(byte opt) { return job.getJobStyle(opt); }
    public Job getJobStyle() { return job.getJobStyle(); }
    public synchronized void changeJob(Job newJob) { job.changeJob(newJob); }
    public Job getJob() { return job.getJob(); }
    public void setJob(Job newJob) { job.setJob(newJob); }
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

    public int getStr() { return stats.getAttr(STR); }
    public int getDex() { return stats.getAttr(DEX); }
    public int getInt() { return stats.getAttr(INT); }
    public int getLuk() { return stats.getAttr(LUK); }


    public void healHpMp() {
        stats.applyUpdate(new StatsUpdate().setHp(30000).setMp(30000));
    }

    public void updateHpMp(int x) {
        stats.applyUpdate(new StatsUpdate().setHp(x).setMp(x));
    }

    public void updateHpMp(int newhp, int newmp) {
        stats.applyUpdate(new StatsUpdate().setHp(newhp).setMp(newmp));
    }

    public void changeHpMp(int newhp, int newmp, boolean silent) {
        StatsUpdate u = new StatsUpdate().setHp(newhp).setMp(newmp);
        if (silent) {
            stats.applyUpdateSilently(u);
        } else {
            stats.applyUpdate(u);
        }
    }

    public void updateHp(int hp) {
        stats.applyUpdate(new StatsUpdate().setHp(hp));
    }

    public void updateMaxHp(int maxhp) {
        stats.applyUpdate(new StatsUpdate().setMaxHp(maxhp));
    }

    public void updateHpMaxHp(int hp, int maxhp) {
        stats.applyUpdate(new StatsUpdate().setHp(hp).setMaxHp(maxhp));
    }

    public void updateMp(int mp) {
        stats.applyUpdate(new StatsUpdate().setMp(mp));
    }

    public void updateMaxMp(int maxmp) {
        stats.applyUpdate(new StatsUpdate().setMaxMp(maxmp));
    }

    public void updateMpMaxMp(int mp, int maxmp) {
        stats.applyUpdate(new StatsUpdate().setMp(mp).setMaxMp(maxmp));
    }

    public void updateMaxHpMaxMp(int maxhp, int maxmp) {
        stats.applyUpdate(new StatsUpdate().setMaxHp(maxhp).setMaxMp(maxmp));
    }

    public int safeAddHP(int delta) {
        return stats.safeAddHP(delta);
    }

    public void addHP(int delta) {
        stats.addHP(delta);
    }

    public void addMP(int delta) {
        stats.addMP(delta);
    }

    public void addMPHP(int hpDelta, int mpDelta) {
        stats.addMPHP(hpDelta, mpDelta);
    }

    void addMaxMPMaxHP(int hpdelta, int mpdelta, boolean silent) {
        stats.addMaxMPMaxHP(hpdelta, mpdelta, silent);
    }

    public void addMaxHP(int delta) {
        stats.addMaxHP(delta);
    }

    public void addMaxMP(int delta) {
        stats.addMaxMP(delta);
    }

    public void reapplyLocalStats() {
        stats.reapplyLocalStats();
    }

    public List<Pair<Stat, Integer>> recalcLocalStats() {
        return stats.recalcLocalStats();
    }

    void updateLocalStats() {
        stats.updateLocalStats();
    }

    void announceStatsUpdate(Map<Stat, Integer> statUpdates) {
        stats.announceStatsUpdate(statUpdates);
    }

    public void hpChangeAction(int oldHp) {
        stats.hpChangeAction(oldHp);
    }

    public boolean applyHpMpChange(int hpCon, int hpchange, int mpchange) {
        return stats.applyHpMpChange(hpCon, hpchange, mpchange);
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

    // ── door 门面 ──

    public boolean canDoor() { return door.canDoor(); }
    public Collection<Door> getDoors() { return door.getDoors(); }
    public Door getPlayerDoor() { return door.getPlayerDoor(); }
    public Door getMainTownDoor() { return door.getMainTownDoor(); }
    public void applyPartyDoor(Door door, boolean partyUpdate) { this.door.applyPartyDoor(door, partyUpdate); }
    public Door removePartyDoor(boolean partyUpdate) { return door.removePartyDoor(partyUpdate); }
    public int getDoorSlot() { return door.getDoorSlot(); }
    public int fetchDoorSlot() { return door.fetchDoorSlot(); }

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
}
