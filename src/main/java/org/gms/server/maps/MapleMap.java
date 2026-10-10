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
package org.gms.server.maps;

import org.gms.client.EffectType;
import org.gms.client.character.Character;
import org.gms.client.character.CharacterRef;
import org.gms.client.character.MapView;
import org.gms.client.messages.MapMonsterHpMessage;
import org.gms.client.messages.MapObjectsViewMessage;
import org.gms.client.Client;
import org.gms.client.autoban.AutobanFactory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.messages.MapCharacterMoveMessage;
import org.gms.client.messages.MapItemDropMessage;
import org.gms.client.messages.MapMonsterDeathMessage;
import org.gms.client.messages.MapMonsterSpawnMessage;
import org.gms.client.messages.MapObjectSpawnMessage;
import org.gms.client.messages.MapQuestCompleteMessage;
import org.gms.client.messages.MapMonsterMoveMessage;
import org.gms.client.pet.Pet;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.config.GameConfig;
import org.gms.constants.id.MapId;
import org.gms.constants.id.MobId;
import org.gms.constants.inventory.ItemConstants;

import org.gms.net.packet.Packet;
import org.gms.remote.RemoteClient;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.coordinator.world.MonsterAggroCoordinator;
import org.gms.net.server.services.task.channel.MobMistService;
import org.gms.net.server.services.task.channel.OverallService;
import org.gms.net.server.services.type.ChannelServices;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.World;
import org.gms.remote.modules.map.client.MonsterMove;
import org.gms.remote.modules.map.client.MoveLife;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.remote.modules.map.client.movement.AbsoluteMove;
import org.gms.remote.modules.map.client.movement.JumpDownMove;
import org.gms.remote.modules.map.client.movement.RelativeMove;
import org.gms.remote.modules.map.client.movement.TeleportMove;
import org.gms.util.NumberTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.ItemInformationProvider;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
import org.gms.server.events.gm.Coconut;
import org.gms.server.events.gm.Fitness;
import org.gms.server.events.gm.Ola;
import org.gms.server.events.gm.OxQuiz;
import org.gms.server.events.gm.Snowball;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.LifeFactory.selfDestruction;
import org.gms.server.life.MobSkill;
import org.gms.server.life.MobSkillFactory;
import org.gms.server.life.MobSkillId;
import org.gms.server.life.MobSkillType;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterDropEntry;
import org.gms.server.life.MonsterGlobalDropEntry;
import org.gms.server.life.MonsterInformationProvider;
import org.gms.server.life.MonsterListener;
import org.gms.server.life.NPC;
import org.gms.server.life.PlayerNPC;
import org.gms.server.life.SpawnPoint;
import org.gms.server.partyquest.CarnivalFactory;
import org.gms.server.partyquest.CarnivalFactory.MCSkill;
import org.gms.server.partyquest.GuardianSpawnPoint;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.gms.util.Randomizer;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;

public class MapleMap {
    private static final Logger log = LoggerFactory.getLogger(MapleMap.class);
    private static final List<MapObjectType> rangedMapobjectTypes = Arrays.asList(MapObjectType.SHOP, MapObjectType.ITEM, MapObjectType.NPC, MapObjectType.MONSTER, MapObjectType.DOOR, MapObjectType.SUMMON, MapObjectType.REACTOR);

    /** 静态内容（WZ 装载期决定，与 {@link MapleMapRef} 共享同一实例；无锁直读） */
    private final MapleMapStatic st;

    private final Map<Integer, MapObject> mapobjects = new LinkedHashMap<>();
    private final Set<Integer> selfDestructives = new LinkedHashSet<>();
    private final Collection<SpawnPoint> monsterSpawn = Collections.synchronizedList(new LinkedList<>());
    private final Collection<SpawnPoint> allMonsterSpawn = Collections.synchronizedList(new LinkedList<>());
    private final AtomicInteger spawnedMonstersOnMap = new AtomicInteger(0);
    private final AtomicInteger droppedItemCount = new AtomicInteger(0);
    private final Collection<CharacterRef> characters = new LinkedHashSet<>();
    private final Map<Integer, Set<Integer>> mapParty = new LinkedHashMap<>();
    private final Map<Integer, Portal> portals = new HashMap<>();
    private final Map<String, Integer> environment = new LinkedHashMap<>();
    private final Map<MapItem, Long> droppedItems = new LinkedHashMap<>();
    private final LinkedList<WeakReference<MapObject>> registeredDrops = new LinkedList<>();
    private final Map<MobLootEntry, Long> mobLootEntries = new HashMap<>(20);
    private final List<Runnable> statUpdateRunnables = new ArrayList<>(50);
    private final AtomicInteger runningOid = new AtomicInteger(1000000001);
    private boolean docked = false;
    private EventInstanceManager event = null;
    private MapEffect mapEffect = null;
    private OxQuiz ox;
    private boolean isOxQuiz = false;
    private boolean dropsOn = true;
    private MonsterAggroCoordinator aggroMonitor = null;   // aggroMonitor activity in sync with itemMonitor
    private ScheduledFuture<?> itemMonitor = null;
    private ScheduledFuture<?> expireItemsTask = null;
    private ScheduledFuture<?> mobSpawnLootTask = null;
    private ScheduledFuture<?> characterStatUpdateTask = null;
    private short itemMonitorTimeout;
    private boolean allowSummons = true; // All maps should have this true at the beginning

    // events
    private boolean eventstarted = false, isMuted = false;
    private Snowball snowball0 = null;
    private Snowball snowball1 = null;
    private Coconut coconut;

    //locks
    private final Lock chrRLock;
    private final Lock chrWLock;
    private final Lock objectRLock;
    private final Lock objectWLock;
    /** player strand 侧句柄（doc/13）：actor shim 归 ref 持有，本类的 shim 用点一律经 ref */
    final MapleMapRef ref;
    /** 近战攻击 phase 2 执行器（map actor 域内，见 Battle） */
    private final Battle battle = new Battle(this);

    private final Lock lootLock = new ReentrantLock(true);

    /** 静态内容视图（同 package 直读；player 侧经 {@link MapleMapRef#statics()}） */
    MapleMapStatic statics() {
        return st;
    }

    public MapleMap(MapleMapStatic st) {
        this.st = st;

        final ReadWriteLock chrLock = new ReentrantReadWriteLock(true);
        chrRLock = chrLock.readLock();
        chrWLock = chrLock.writeLock();

        final ReadWriteLock objectLock = new ReentrantReadWriteLock(true);
        objectRLock = objectLock.readLock();
        objectWLock = objectLock.writeLock();

        aggroMonitor = new MonsterAggroCoordinator();
        this.ref = new MapleMapRef(this, "map-" + st.mapid() + "@c" + st.channel());
    }

    /** 本图的 player strand 侧句柄（规范唯一：identity 即本图 identity） */
    public MapleMapRef ref() {
        return ref;
    }

    public void setEventInstance(EventInstanceManager eim) {
        event = eim;
    }

    /** player→map 通知类调用：经 shim 异步执行（FIFO 派发序，不串行化，doc/13 §1）——迁移期过渡入口，新代码走 {@link MapleMapRef#post} */
    public void post(String task, Runnable r) {
        ref.post(task, r);
    }

    /** player→map 排序敏感缝合点：经 shim 同步完成（返回后本线程还要发自己的包/依赖其效果时用）——迁移期过渡入口，新代码走 {@link MapleMapRef#runIn} */
    public void runIn(String task, Runnable r) {
        ref.runIn(task, r);
    }

    /** 离图单笔投递（{@link MapleMapRef#postLeaveMap} 的 MapleMap 门面，Client 离图路径用） */
    public void postLeaveMap(RemoveFacts facts) {
        ref.postLeaveMap(facts);
    }

    public EventInstanceManager getEventInstance() {
        return event;
    }

    public Rectangle getMapArea() {
        return st.mapArea();
    }

    public int getWorld() {
        return st.world();
    }

    public void broadcastPacket(CharacterRef source, Packet packet) {
        broadcastPacket(packet, chr -> chr != null && !chr.isClientDisconnected() && chr != source);
    }

    public void broadcastGMPacket(CharacterRef source, Packet packet) {
        broadcastPacket(packet, chr -> chr != null && !chr.isClientDisconnected() && chr != source && chr.gmLevel() >= source.gmLevel());
    }

    private void broadcastPacket(Packet packet, Predicate<CharacterRef> chrFilter) {
        chrRLock.lock();
        try {
            characters.stream()
                    .filter(chrFilter)
                    .forEach(chr -> chr.sendPacket(packet));
        } finally {
            chrRLock.unlock();
        }
    }

    public void toggleDrops() {
        this.dropsOn = !dropsOn;
    }

    /** 怪物死亡场景事件全员投递（与 spawnAndPostMapObject 成对：spawn ↔ death） */
    private void postMapMonsterDeath(int oid, int animation) {
        final MapMonsterDeathMessage msg = new MapMonsterDeathMessage(getId(), oid, animation);
        chrRLock.lock();
        try {
            for (CharacterRef chr : characters) {
                chr.post(msg);
            }
        } finally {
            chrRLock.unlock();
        }
    }

    /** 视野判定阈值（player 域同款判定用；use_max_range=true 时恒可见） */
    public static double getRangedDistance() {
        return GameConfig.getServerBoolean("use_max_range") ? Double.POSITIVE_INFINITY : 722500;
    }

    public List<MapObject> getMapObjectsInRect(Rectangle box, List<MapObjectType> types) {
        objectRLock.lock();
        final List<MapObject> ret = new LinkedList<>();
        try {
            for (MapObject l : mapobjects.values()) {
                if (types.contains(l.getType())) {
                    if (box.contains(l.getPosition())) {
                        ret.add(l);
                    }
                }
            }
        } finally {
            objectRLock.unlock();
        }
        return ret;
    }

    public int getId() {
        return st.mapid();
    }

    public Channel getChannelServer() {
        return Server.getInstance().getWorld(st.world()).getChannel(st.channel());
    }

    public World getWorldServer() {
        return Server.getInstance().getWorld(st.world());
    }

    public MapleMap getReturnMap() {
        if (st.returnMapId() == MapId.NONE) {
            return this;
        }
        return getChannelServer().getMapFactory().getMap(st.returnMapId());
    }

    public int getReturnMapId() {
        return st.returnMapId();
    }

    public MapleMap getForcedReturnMap() {
        return getChannelServer().getMapFactory().getMap(st.forcedReturnMap());
    }

    public int getForcedReturnId() {
        return st.forcedReturnMap();
    }

    public int getTimeLimit() {
        return st.timeLimit();
    }

    public int getTimeLeft() {
        return (int) ((st.mapTimer() - System.currentTimeMillis()) / 1000);
    }

    public void setReactorState() {
        for (MapObject o : getMapObjects()) {
            if (o.getType() == MapObjectType.REACTOR) {
                if (((Reactor) o).getState() < 1) {
                    Reactor mr = (Reactor) o;
                    mr.lockReactor();
                    try {
                        mr.resetReactorActions(1);
                        broadcastMessage(PacketCreator.triggerReactor((Reactor) o, 1));
                    } finally {
                        mr.unlockReactor();
                    }
                }
            }
        }
    }

    public final void limitReactor(final int rid, final int num) {
        List<Reactor> toDestroy = new ArrayList<>();
        Map<Integer, Integer> contained = new LinkedHashMap<>();

        for (MapObject obj : getReactors()) {
            Reactor mr = (Reactor) obj;
            if (contained.containsKey(mr.getId())) {
                if (contained.get(mr.getId()) >= num) {
                    toDestroy.add(mr);
                } else {
                    contained.put(mr.getId(), contained.get(mr.getId()) + 1);
                }
            } else {
                contained.put(mr.getId(), 1);
            }
        }

        for (Reactor mr : toDestroy) {
            destroyReactor(mr.getObjectId());
        }
    }

    public boolean isAllReactorState(final int reactorId, final int state) {
        for (MapObject mo : getReactors()) {
            Reactor r = (Reactor) mo;

            if (r.getId() == reactorId && r.getState() != state) {
                return false;
            }
        }
        return true;
    }

    public int getCurrentPartyId() {
        for (CharacterRef chr : this.getCharacters()) {
            if (chr.getPartyId() > 0) {   // 视图域契约：0 = 无队伍（发布侧已归一，见 CharacterRef.publishView）
                return chr.getPartyId();
            }
        }
        return -1;
    }

    public void addPlayerNPCMapObject(PlayerNPC pnpcobject) {
        objectWLock.lock();
        try {
            this.mapobjects.put(pnpcobject.getObjectId(), pnpcobject);
        } finally {
            objectWLock.unlock();
        }
    }

    public void addMapObject(MapObject mapobject) {
        int curOID = getUsableOID();

        objectWLock.lock();
        try {
            mapobject.setObjectId(curOID);
            this.mapobjects.put(curOID, mapobject);
        } finally {
            objectWLock.unlock();
        }
    }

    public void addSelfDestructive(Monster mob) {
        if (mob.getStats().selfDestruction() != null) {
            this.selfDestructives.add(mob.getObjectId());
        }
    }

    public boolean removeSelfDestructive(int mapobjectid) {
        return this.selfDestructives.remove(mapobjectid);
    }

    /**
     * 对象落地（全员投递，visible 判定在 viewer 域）：oid 分配/注册 → 包构建（{@code packets}
     * 惰性求值——oid 是包主键，快照必须在 setObjectId 之后冻结，同 {@link #spawnAndAddRangedMapObject}
     * 教义；预构建包曾把 oid=0 冻入刷新 spawn = 死怪刷新双生幽灵）→ 值消息发全图玩家；
     * 各 player strand 按 (自身位置, viewEntry position) 判 visible——可见才登记 MapView 并向
     * client 直发包，不可见整个丢弃。
     */
    private void spawnAndPostMapObject(MapObject mapobject, Supplier<List<Packet>> packets) {
        int curOID = getUsableOID();

        chrRLock.lock();
        objectWLock.lock();
        try {
            mapobject.setObjectId(curOID);
            this.mapobjects.put(curOID, mapobject);
        } finally {
            objectWLock.unlock();
            chrRLock.unlock();
        }

        final MapView.Entry viewEntry = viewEntry(mapobject);
        final List<Packet> built = packets.get();
        chrRLock.lock();
        try {
            for (CharacterRef chr : characters) {
                chr.post(new MapObjectSpawnMessage(getId(), viewEntry, built));
            }
        } finally {
            chrRLock.unlock();
        }
    }

    /**
     * 怪物落地（{@code PacketCreator.spawnMonster/spawnFakeMonster} 系调用点专用）：
     * 落地帧值化（{@link MapMonsterSpawnMessage}）——包构建移 viewer 域 freeze，oid 分配后
     * post（值主键语义同 spawnAndPostMapObject 教义）；fake = 假怪帧（CONTROL 头 kind 5）。
     */
    private void spawnAndPostMonster(Monster monster, boolean newSpawn, int effect, boolean fake) {
        int curOID = getUsableOID();

        chrRLock.lock();
        objectWLock.lock();
        try {
            monster.setObjectId(curOID);
            this.mapobjects.put(curOID, monster);
        } finally {
            objectWLock.unlock();
            chrRLock.unlock();
        }

        final MapView.Entry viewEntry = viewEntry(monster);
        final MapView.MonsterView view = MapView.MonsterView.of(monster);   // post 时点冻结
        chrRLock.lock();
        try {
            for (CharacterRef chr : characters) {
                chr.post(new MapMonsterSpawnMessage(getId(), viewEntry, newSpawn, effect, fake, view));
            }
        } finally {
            chrRLock.unlock();
        }
    }

    /**
     * MapItem 专用：每 viewer post 值消息（bakery/Client 直发不参与）。值快照在 oid 分配
     * 之后冻结——oid 是包主键，必须 setObjectId 后构建。全员投递，visible/needQuestItem
     * 判定在 viewer 域（MessageDispatcher）。
     */
    private void spawnAndAddRangedMapObject(MapItem mapobject, int dropperOid, Point dropfrom, Point dropto, byte mod) {
        int curOID = getUsableOID();

        chrRLock.lock();
        objectWLock.lock();
        try {
            mapobject.setObjectId(curOID);
            this.mapobjects.put(curOID, mapobject);
        } finally {
            objectWLock.unlock();
            chrRLock.unlock();
        }

        final MapItemDropMessage dropMessage = MapItemDropMessage.of(getId(), mapobject, dropperOid, dropfrom, dropto, mod);
        chrRLock.lock();
        try {
            for (CharacterRef chr : characters) {
                // 过滤（needQuestItem）+ visible 判定 + 视图登记全在 viewer 域（MessageDispatcher drop case）
                chr.post(dropMessage);
            }
        } finally {
            chrRLock.unlock();
        }
    }

    private int getUsableOID() {
        objectRLock.lock();
        try {
            int curOid;

            // clashes with playernpc on curOid >= 2147000000, developernpc uses >= 2147483000
            do {
                if ((curOid = runningOid.incrementAndGet()) >= 2147000000) {
                    runningOid.set(curOid = 1000000001);
                }
            } while (mapobjects.containsKey(curOid));

            return curOid;
        } finally {
            objectRLock.unlock();
        }
    }

    /**
     * 地图对象值快照提取（MapView 值化过渡）：monster/item/reactor/npc 带模板 id，
     * 其余类型暂记 0（按需补；将来 MapObjectView 接口接管）。
     */
    private static MapView.Entry viewEntry(MapObject mo) {
        final int id = switch (mo.getType()) {
            case MONSTER -> ((Monster) mo).getId();
            case ITEM -> ((MapItem) mo).getItemId();
            case REACTOR -> ((Reactor) mo).getId();
            case NPC -> ((NPC) mo).getId();
            default -> 0;
        };
        // visible=true = map 侧范围预过滤语义（登记即可见）；判定权移交 player 域后由 apply 侧覆写
        return new MapView.Entry(mo.getObjectId(), new MapView.MapObjectInfo(mo.getType(), id, mo.getPosition(), true));
    }

    public void removeMapObject(int num) {
        objectWLock.lock();
        try {
            this.mapobjects.remove(num);
        } finally {
            objectWLock.unlock();
        }
    }

    public void removeMapObject(final MapObject obj) {
        removeMapObject(obj.getObjectId());
    }

    private Point calcPointBelow(Point initial) {
        return MapleMapStatic.calcPointBelow(st.footholds(), initial);
    }

    public Point calcDropPos(Point initial, Point fallback) {
        if (initial.x < st.xLimits().left) {
            initial.x = st.xLimits().left;
        } else if (initial.x > st.xLimits().right) {
            initial.x = st.xLimits().right;
        }

        Point ret = calcPointBelow(new Point(initial.x, initial.y - 85));   // actual drop ranges: default - 120, explosive - 360
        if (ret == null) {
            ret = MapleMapStatic.bsearchDropPos(st.footholds(), initial, fallback);
        }

        if (!st.mapArea().contains(ret)) { // found drop pos outside the map :O
            return fallback;
        }

        return ret;
    }

    public boolean canDeployDoor(Point pos) {
        Point toStep = calcPointBelow(pos);
        return toStep != null && toStep.distance(pos) <= 42;
    }

    /**
     * Fetches angle relative between spawn and door points where 3 O'Clock is 0
     * and 12 O'Clock is 270 degrees
     *
     * @param spawnPoint
     * @param doorPoint
     * @return angle in degress from 0-360.
     */
    private static double getAngle(Point doorPoint, Point spawnPoint) {
        double dx = doorPoint.getX() - spawnPoint.getX();
        // Minus to correct for coord re-mapping
        double dy = -(doorPoint.getY() - spawnPoint.getY());

        double inRads = Math.atan2(dy, dx);

        // We need to map to coord system when 0 degree is at 3 O'st.clock(), 270 at 12 O'st.clock()
        if (inRads < 0) {
            inRads = Math.abs(inRads);
        } else {
            inRads = 2 * Math.PI - inRads;
        }

        return Math.toDegrees(inRads);
    }

    /**
     * Converts angle in degrees to rounded cardinal coordinate.
     *
     * @param angle
     * @return correspondent coordinate.
     */
    public static String getRoundedCoordinate(double angle) {
        String[] directions = {"E", "SE", "S", "SW", "W", "NW", "N", "NE", "E"};
        return directions[(int) Math.round(((angle % 360) / 45))];
    }

    public Pair<String, Integer> getDoorPositionStatus(Point pos) {
        Portal portal = findClosestPlayerSpawnpoint(pos);

        double angle = getAngle(portal.getPosition(), pos);
        double distn = pos.distanceSq(portal.getPosition());

        if (distn <= 777777.7) {
            return null;
        }

        distn = Math.sqrt(distn);
        return new Pair<>(getRoundedCoordinate(angle), (int) distn);
    }

    private static void sortDropEntries(List<MonsterDropEntry> from, List<MonsterDropEntry> item, List<MonsterDropEntry> visibleQuest, List<MonsterDropEntry> otherQuest, CharacterRef chr) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        for (MonsterDropEntry mde : from) {
            if (!ii.isQuestItem(mde.itemId)) {
                item.add(mde);
            } else {
                if (chr.needQuestItem(mde.questid, mde.itemId)) {
                    visibleQuest.add(mde);
                } else {
                    otherQuest.add(mde);
                }
            }
        }
    }

    private byte dropItemsFromMonsterOnMap(List<MonsterDropEntry> dropEntry, Point pos, byte d, double chRate, byte droptype, int mobpos, Battle.DropEntitlement ent, CharacterRef chr, Monster mob) {
        if (dropEntry.isEmpty()) {
            return d;
        }

        Collections.shuffle(dropEntry);

        ItemSlot idrop;
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        for (final MonsterDropEntry de : dropEntry) {
            // 卡倍率：ent 路径取 phase 1 解析值（itemId=0 → mesoDropRate；缺省 1.0），legacy 路径本体活读。
            // float→double 化登记：滚动乘法改 double，RNG 阈值 sub-ulp（见 Battle.DropEntitlement 注）。
            final double cardRate = ent != null
                    ? (de.itemId == 0 ? ent.mesoDropRate() : ent.itemCardRate(mob.getId(), de.itemId))
                    : chr.getCardRate(de.itemId);
            int dropChance = (int) Math.min(de.chance * chRate * cardRate, Integer.MAX_VALUE);

            if (Randomizer.nextInt(999999) < dropChance) {
                if (droptype == 3) {
                    pos.x = mobpos + ((d % 2 == 0) ? (40 * ((d + 1) / 2)) : -(40 * (d / 2)));
                } else {
                    pos.x = mobpos + ((d % 2 == 0) ? (25 * ((d + 1) / 2)) : -(25 * (d / 2)));
                }
                if (de.itemId == 0) { // meso
                    int mesos = Randomizer.nextInt(de.Maximum - de.Minimum) + de.Minimum;

                    if (mesos > 0) {
                        if (ent != null) {
                            // 金额轴合并终值（4111001 buff 已在 phase 1 折入；单次 double 舍入）
                            mesos = NumberTool.doubleToInt(mesos * ent.mesoRate());
                        } else {
                            if (chr.getBuffedValue(EffectType.MESOUP) != null) {
                                mesos = NumberTool.doubleToInt(mesos * chr.getBuffedValue(EffectType.MESOUP).doubleValue() / 100.0);
                            }
                            mesos = NumberTool.floatToInt(mesos * chr.getMesoRate());
                        }
                        if (mesos <= 0) {
                            mesos = Integer.MAX_VALUE;
                        }

                        spawnMesoDrop(mesos, calcDropPos(pos, mob.getPosition()), mob, chr, false, droptype);
                    }
                } else {
                    if (ItemConstants.getInventoryType(de.itemId) == InventoryType.EQUIP) {
                        ItemSlot equipDrop = ii.getEquipById(de.itemId);
                        ii.randomizeStats(equipDrop.getEquipInfo());
                        idrop = equipDrop;
                    } else {
                        idrop = new ItemSlot(de.itemId, (short) 0, (short) ((de.Maximum != 1 && de.Maximum > de.Minimum)? Randomizer.nextInt(de.Maximum - de.Minimum) + de.Minimum : de.Maximum));
                    }
                    spawnDrop(idrop, calcDropPos(pos, mob.getPosition()), mob, chr, droptype, de.questid);
                }
                d++;
            }
        }

        return d;
    }

    private byte dropGlobalItemsFromMonsterOnMap(List<MonsterGlobalDropEntry> globalEntry, Point pos, byte d, byte droptype, int mobpos, CharacterRef chr, Monster mob) {
        Collections.shuffle(globalEntry);

        ItemSlot idrop;
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        for (final MonsterGlobalDropEntry de : globalEntry) {
            if (Randomizer.nextInt(999999) < de.chance) {
                if (droptype == 3) {
                    pos.x = mobpos + (d % 2 == 0 ? (40 * (d + 1) / 2) : -(40 * (d / 2)));
                } else {
                    pos.x = mobpos + ((d % 2 == 0) ? (25 * (d + 1) / 2) : -(25 * (d / 2)));
                }
                if (de.itemId != 0) {
                    if (ItemConstants.getInventoryType(de.itemId) == InventoryType.EQUIP) {
                        ItemSlot equipDrop = ii.getEquipById(de.itemId);
                        ii.randomizeStats(equipDrop.getEquipInfo());
                        idrop = equipDrop;
                    } else {
                        idrop = new ItemSlot(de.itemId, (short) 0, (short) (de.Maximum != 1 ? Randomizer.nextInt(de.Maximum - de.Minimum) + de.Minimum : 1));
                    }
                    spawnDrop(idrop, calcDropPos(pos, mob.getPosition()), mob, chr, droptype, de.questid);
                    d++;
                }
            }
        }

        return d;
    }

    private void dropFromMonster(final CharacterRef chr, final Monster mob, final boolean useBaseRate) {
        if (mob.dropsDisabled() || !dropsOn) {
            return;
        }

        // 掉落权益快照（普攻链路）：取不到 = 非普攻死亡（魔法/脚本/friendly 等），整段走 legacy
        final Battle.DropEntitlement ent = useBaseRate ? null : mob.getDropEntitlement(chr.getId());

        final byte droptype = (byte) (mob.getStats().isExplosiveReward() ? 3 : mob.getStats().isFfaLoot() ? 2 : chr.getPartyId() > 0 ? 1 : 0);
        final int mobpos = mob.getPosition().x;
        Point pos = new Point(0, mob.getPosition().y);

        final double chRate;
        if (ent != null) {
            // 用户侧最终乘算倍率（family 已折叠；boss 统一用 dropRate）；SHOWDOWN 是 mob 侧状态，本域乘
            double rate = ent.dropRate();
            MonsterStatusEffect stati = mob.getStati(MonsterStatus.SHOWDOWN);
            if (stati != null) {
                rate *= (stati.getStati().get(MonsterStatus.SHOWDOWN).doubleValue() / 100.0 + 1.0);
            }
            chRate = rate;
        } else {
            float legacyRate = !mob.isBoss() ? chr.getDropRate() : chr.getBossDropRate();

            MonsterStatusEffect stati = mob.getStati(MonsterStatus.SHOWDOWN);
            if (stati != null) {
                legacyRate *= (stati.getStati().get(MonsterStatus.SHOWDOWN).doubleValue() / 100.0 + 1.0);
            }

            if (chr.isFamilyBuff()) {
                legacyRate *= chr.getFamilyDrop();
            }

            if (useBaseRate) {
                legacyRate = 1;
            }
            chRate = legacyRate;
        }

        final MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
        final ItemInformationProvider ii = ItemInformationProvider.getInstance();
        final List<MonsterGlobalDropEntry> globalEntry = mi.getRelevantGlobalDrops(this.getId());

        final List<MonsterDropEntry> dropEntry = new ArrayList<>();
        final List<List<MonsterDropEntry>> questGroups;   // 段序 = 掉落序（legacy: visible/other；ent: mvp/others/free）
        if (ent != null) {
            // 点2+3：总是掉落全部物品（use_spawn_relevant_loot 旁路），四段按快照需求集排序——
            // 需求谓词已在 phase 1 冻结为 itemId 集，本域只做成员匹配，不触 needQuestItem
            List<MonsterDropEntry> lootEntry = mi.retrieveEffectiveDrop(mob.getId());
            if (lootEntry.isEmpty()) {   // thanks resinate
                return;
            }
            final List<MonsterDropEntry> questMvp = new ArrayList<>();
            final List<MonsterDropEntry> questOthers = new ArrayList<>();
            final List<MonsterDropEntry> questFree = new ArrayList<>();
            for (MonsterDropEntry de : lootEntry) {
                if (!ii.isQuestItem(de.itemId)) {
                    dropEntry.add(de);
                } else if (ent.needsQuestItem(de.itemId)) {
                    questMvp.add(de);                                              // mvp（dropOwner）需求
                } else if (mob.hasOtherEntitledQuestNeed(de.itemId, chr.getId())) {
                    questOthers.add(de);                                           // 其他攻击者需求
                } else {
                    questFree.add(de);                                             // 无人需求（点4 前过渡期不可见）
                }
            }
            questGroups = List.of(questMvp, questOthers, questFree);
        } else {
            List<MonsterDropEntry> lootEntry = GameConfig.getServerBoolean("use_spawn_relevant_loot") ? mob.retrieveRelevantDrops() : mi.retrieveEffectiveDrop(mob.getId());
            if (lootEntry.isEmpty()) {   // thanks resinate
                return;
            }
            final List<MonsterDropEntry> visibleQuestEntry = new ArrayList<>();
            final List<MonsterDropEntry> otherQuestEntry = new ArrayList<>();
            sortDropEntries(lootEntry, dropEntry, visibleQuestEntry, otherQuestEntry, chr);     // thanks Articuno, Limit, Rohenn for noticing quest loots not showing up in only-quest item drops scenario
            questGroups = List.of(visibleQuestEntry, otherQuestEntry);
        }

        registerMobItemDrops(droptype, mobpos, chRate, pos, dropEntry, questGroups, globalEntry, chr, mob, ent);
    }

    public void dropItemsFromMonster(List<MonsterDropEntry> list, final CharacterRef chr, final Monster mob) {
        if (mob.dropsDisabled() || !dropsOn) {
            return;
        }

        final byte droptype = (byte) (chr.getPartyId() > 0 ? 1 : 0);
        final int mobpos = mob.getPosition().x;
        double chRate = 1000000;   // guaranteed item drop
        byte d = 1;
        Point pos = new Point(0, mob.getPosition().y);

        dropItemsFromMonsterOnMap(list, pos, d, chRate, droptype, mobpos, null, chr, mob);
    }

    public void dropFromFriendlyMonster(final CharacterRef chr, final Monster mob) {
        dropFromMonster(chr, mob, true);
    }

    public void dropFromReactor(final CharacterRef chr, final Reactor reactor, ItemSlot drop, Point dropPos, short questid) {
        spawnDrop(drop, this.calcDropPos(dropPos, reactor.getPosition()), reactor, chr, (byte) (chr.getPartyId() > 0 ? 1 : 0), questid);
    }

    private void stopItemMonitor() {
        itemMonitor.cancel(false);
        itemMonitor = null;

        expireItemsTask.cancel(false);
        expireItemsTask = null;

        if (GameConfig.getServerBoolean("use_spawn_loot_on_animation")) {
            mobSpawnLootTask.cancel(false);
            mobSpawnLootTask = null;
        }

        characterStatUpdateTask.cancel(false);
        characterStatUpdateTask = null;
    }

    private void cleanItemMonitor() {
        objectWLock.lock();
        try {
            registeredDrops.removeAll(Collections.singleton(null));
        } finally {
            objectWLock.unlock();
        }
    }

    private void startItemMonitor() {
        chrWLock.lock();
        try {
            if (itemMonitor != null) {
                return;
            }

            itemMonitor = TimerManager.getInstance().register(() -> {
                chrWLock.lock();
                try {
                    if (characters.isEmpty()) {
                        if (itemMonitorTimeout == 0) {
                            if (itemMonitor != null) {
                                stopItemMonitor();
                                aggroMonitor.stopAggroCoordinator();
                            }

                            return;
                        } else {
                            itemMonitorTimeout--;
                        }
                    } else {
                        itemMonitorTimeout = 1;
                    }
                } finally {
                    chrWLock.unlock();
                }

                boolean tryClean;
                objectRLock.lock();
                try {
                    tryClean = registeredDrops.size() > 70;
                } finally {
                    objectRLock.unlock();
                }

                if (tryClean) {
                    cleanItemMonitor();
                }
            }, GameConfig.getServerLong("item_monitor_time"), GameConfig.getServerLong("item_monitor_time"));

            expireItemsTask = TimerManager.getInstance().register(this::makeDisappearExpiredItemDrops, GameConfig.getServerLong("item_expire_check"), GameConfig.getServerLong("item_expire_check"));

            if (GameConfig.getServerBoolean("use_spawn_loot_on_animation")) {
                lootLock.lock();
                try {
                    mobLootEntries.clear();
                } finally {
                    lootLock.unlock();
                }

                mobSpawnLootTask = TimerManager.getInstance().register(this::spawnMobItemDrops, 200, 200);
            }

            characterStatUpdateTask = TimerManager.getInstance().register(this::runCharacterStatUpdate, 200, 200);

            itemMonitorTimeout = 1;
        } finally {
            chrWLock.unlock();
        }
    }

    private boolean hasItemMonitor() {
        chrRLock.lock();
        try {
            return itemMonitor != null;
        } finally {
            chrRLock.unlock();
        }
    }

    public int getDroppedItemCount() {
        return droppedItemCount.get();
    }

    private void instantiateItemDrop(MapItem mdrop) {
        if (droppedItemCount.get() >= GameConfig.getServerInt("item_limit_on_map")) {
            MapObject mapobj;

            do {
                mapobj = null;

                objectWLock.lock();
                try {
                    while (mapobj == null) {
                        if (registeredDrops.isEmpty()) {
                            break;
                        }
                        mapobj = registeredDrops.remove(0).get();
                    }
                } finally {
                    objectWLock.unlock();
                }
            } while (!makeDisappearItemFromMap(mapobj));
        }

        objectWLock.lock();
        try {
            registerItemDrop(mdrop);
            registeredDrops.add(new WeakReference<>(mdrop));
        } finally {
            objectWLock.unlock();
        }

        droppedItemCount.incrementAndGet();
    }

    private void registerItemDrop(MapItem mdrop) {
        droppedItems.put(mdrop, !st.everlast() ? Server.getInstance().getCurrentTime() + GameConfig.getServerLong("item_expire_time") : Long.MAX_VALUE);
    }

    private void unregisterItemDrop(MapItem mdrop) {
        objectWLock.lock();
        try {
            droppedItems.remove(mdrop);
        } finally {
            objectWLock.unlock();
        }
    }

    private void makeDisappearExpiredItemDrops() {
        List<MapItem> toDisappear = new LinkedList<>();

        objectRLock.lock();
        try {
            long timeNow = Server.getInstance().getCurrentTime();

            for (Entry<MapItem, Long> it : droppedItems.entrySet()) {
                if (it.getValue() < timeNow) {
                    toDisappear.add(it.getKey());
                }
            }
        } finally {
            objectRLock.unlock();
        }

        for (MapItem mmi : toDisappear) {
            makeDisappearItemFromMap(mmi);
        }

        objectWLock.lock();
        try {
            for (MapItem mmi : toDisappear) {
                droppedItems.remove(mmi);
            }
        } finally {
            objectWLock.unlock();
        }
    }

    private void registerMobItemDrops(byte droptype, int mobpos, double chRate, Point pos, List<MonsterDropEntry> dropEntry, List<List<MonsterDropEntry>> questGroups, List<MonsterGlobalDropEntry> globalEntry, CharacterRef chr, Monster mob, Battle.DropEntitlement ent) {
        MobLootEntry mle = new MobLootEntry(droptype, mobpos, chRate, pos, dropEntry, questGroups, globalEntry, chr, mob, ent);

        if (GameConfig.getServerBoolean("use_spawn_loot_on_animation")) {
            int animationTime = mob.getAnimationTime("die1");

            lootLock.lock();
            try {
                long timeNow = Server.getInstance().getCurrentTime();
                mobLootEntries.put(mle, timeNow + ((long) (0.42 * animationTime)));
            } finally {
                lootLock.unlock();
            }
        } else {
            mle.run();
        }
    }

    private void spawnMobItemDrops() {
        Set<Entry<MobLootEntry, Long>> mleList;

        lootLock.lock();
        try {
            mleList = new HashSet<>(mobLootEntries.entrySet());
        } finally {
            lootLock.unlock();
        }

        long timeNow = Server.getInstance().getCurrentTime();
        List<MobLootEntry> toRemove = new LinkedList<>();
        for (Entry<MobLootEntry, Long> mlee : mleList) {
            if (mlee.getValue() < timeNow) {
                toRemove.add(mlee.getKey());
            }
        }

        if (!toRemove.isEmpty()) {
            List<MobLootEntry> toSpawnLoot = new LinkedList<>();

            lootLock.lock();
            try {
                for (MobLootEntry mle : toRemove) {
                    Long mler = mobLootEntries.remove(mle);
                    if (mler != null) {
                        toSpawnLoot.add(mle);
                    }
                }
            } finally {
                lootLock.unlock();
            }

            for (MobLootEntry mle : toSpawnLoot) {
                mle.run();
            }
        }
    }

    private List<MapItem> getDroppedItems() {
        objectRLock.lock();
        try {
            return new LinkedList<>(droppedItems.keySet());
        } finally {
            objectRLock.unlock();
        }
    }

    public int getDroppedItemsCountById(int itemid) {
        int count = 0;
        for (MapItem mmi : getDroppedItems()) {
            if (mmi.getItemId() == itemid) {
                count++;
            }
        }

        return count;
    }

    public void pickItemDrop(Packet pickupPacket, MapItem mdrop) { // mdrop must be already locked and not-pickedup checked at this point
        broadcastMessage(pickupPacket, mdrop.getPosition());

        droppedItemCount.decrementAndGet();
        this.removeMapObject(mdrop);
        mdrop.setPickedUp(true);
        unregisterItemDrop(mdrop);
    }

    public List<MapItem> updatePlayerItemDropsToParty(int partyid, int charid, List<CharacterRef> partyMembers, CharacterRef partyLeaver) {
        final CharacterRef leaver = partyLeaver;
        List<MapItem> partyDrops = new LinkedList<>();

        // owner 字段(character_ownerid / party_ownerid)是实例字段,持 itemLock 的
        // 路径会写它们,所以这里必须持同一把锁读,避免锁外读出撕裂值。
        // 同时把对队员/leaver 的 sendPacket 收集到 packetHolder 中,
        // 离开锁再发,避免 itemLock 持锁期间做网络 I/O。
        Map<CharacterRef, List<Packet>> packetHolder = new HashMap<>();

        for (MapItem mdrop : getDroppedItems()) {
            mdrop.lockItem();
            try {
                if (mdrop.isPickedUp()) {
                    continue;
                }

                if (mdrop.getOwnerIdLocked() == charid) {
                    mdrop.setPartyOwnerIdLocked(partyid);

                    Packet removePacket = PacketCreator.silentRemoveItemFromMap(mdrop.getObjectId());
                    Packet updatePacket = PacketCreator.updateMapItemObject(mdrop, partyLeaver == null);

                    for (CharacterRef mc : partyMembers) {
                        if (this.equals(mc.getMap())) {
                            packetHolder.computeIfAbsent(mc, k -> new ArrayList<>()).add(removePacket);

                            if (mc.needQuestItem(mdrop.getQuest(), mdrop.getItemId())) {
                                packetHolder.get(mc).add(updatePacket);
                            }
                        }
                    }

                    if (partyLeaver != null) {
                        if (this.equals(partyLeaver.getMap())) {
                            packetHolder.computeIfAbsent(leaver, k -> new ArrayList<>()).add(removePacket);

                            if (partyLeaver.needQuestItem(mdrop.getQuest(), mdrop.getItemId())) {
                                packetHolder.get(partyLeaver).add(PacketCreator.updateMapItemObject(mdrop, true));
                            }
                        }
                    }
                } else if (partyid != -1 && mdrop.getPartyOwnerIdLocked() == partyid) {
                    partyDrops.add(mdrop);
                }
            } finally {
                mdrop.unlockItem();
            }
        }

        for (Map.Entry<CharacterRef, List<Packet>> e : packetHolder.entrySet()) {
            for (Packet p : e.getValue()) {
                e.getKey().sendPacket(p);
            }
        }

        return partyDrops;
    }

    public void updatePartyItemDropsToNewcomer(CharacterRef cr, List<MapItem> partyItems) {
        final CharacterRef newcomer = cr;
        // 同样:itemLock 内只构造 packet,持锁期间不做 sendPacket。
        for (MapItem mdrop : partyItems) {
            Packet removePacket;
            Packet updatePacket;

            mdrop.lockItem();
            try {
                if (mdrop.isPickedUp()) {
                    continue;
                }

                removePacket = PacketCreator.silentRemoveItemFromMap(mdrop.getObjectId());
                updatePacket = PacketCreator.updateMapItemObject(mdrop, true);
            } finally {
                mdrop.unlockItem();
            }

            if (newcomer != null && this.equals(newcomer.getMap())) {
                newcomer.sendPacket(removePacket);
                if (newcomer.needQuestItem(mdrop.getQuest(), mdrop.getItemId())) {
                    newcomer.sendPacket(updatePacket);
                }
            }
        }
    }

    private void spawnDrop(final ItemSlot idrop, final Point dropPos, final MapObject dropper, final CharacterRef chr, final byte droptype, final short questid) {
        final MapItem mdrop = new MapItem(idrop, dropPos, dropper, chr, droptype, false, questid);
        mdrop.setDropTime(Server.getInstance().getCurrentTime());
        // 值消息投递（原 bakery：needQuestItem 过滤 + unref 构包 + getClient 直发，整体搬 viewer 域；过滤规则原样）
        spawnAndAddRangedMapObject(mdrop, dropper.getObjectId(), dropper.getPosition(), dropPos, (byte) 1);

        instantiateItemDrop(mdrop);
        activateItemReactors(mdrop, chr);
    }

    public final void spawnMesoDrop(final int meso, final Point position, final MapObject dropper, final CharacterRef owner, final boolean playerDrop, final byte droptype) {
        final Point droppos = calcDropPos(position, position);
        final MapItem mdrop = new MapItem(meso, droppos, dropper, owner, droptype, playerDrop);
        mdrop.setDropTime(Server.getInstance().getCurrentTime());
        // 值消息投递（原 bakery 无条件直发；viewer 过滤 needQuestItem(-1,·) 恒真 = 等价）
        spawnAndAddRangedMapObject(mdrop, dropper.getObjectId(), dropper.getPosition(), droppos, (byte) 1);

        instantiateItemDrop(mdrop);
    }

    public final void disappearingItemDrop(final MapObject dropper, final CharacterRef owner, final ItemSlot item, final Point pos) {
        final Point droppos = calcDropPos(pos, pos);
        final MapItem mdrop = new MapItem(item, droppos, dropper, owner, (byte) 1, false);

        mdrop.lockItem();
        try {
            broadcastItemDropMessage(mdrop, dropper.getPosition(), droppos, (byte) 3, mdrop.getPosition());
        } finally {
            mdrop.unlockItem();
        }
    }

    public final void disappearingMesoDrop(final int meso, final MapObject dropper, final CharacterRef owner, final Point pos) {
        final Point droppos = calcDropPos(pos, pos);
        final MapItem mdrop = new MapItem(meso, droppos, dropper, owner, (byte) 1, false);

        mdrop.lockItem();
        try {
            broadcastItemDropMessage(mdrop, dropper.getPosition(), droppos, (byte) 3, mdrop.getPosition());
        } finally {
            mdrop.unlockItem();
        }
    }

    public Monster getMonsterById(int id) {
        objectRLock.lock();
        try {
            for (MapObject obj : mapobjects.values()) {
                if (obj.getType() == MapObjectType.MONSTER) {
                    if (((Monster) obj).getId() == id) {
                        return (Monster) obj;
                    }
                }
            }
        } finally {
            objectRLock.unlock();
        }
        return null;
    }

    public int countMonster(int id) {
        return countMonster(id, id);
    }

    public int countMonster(int minid, int maxid) {
        int count = 0;
        for (MapObject m : getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.MONSTER))) {
            Monster mob = (Monster) m;
            if (mob.getId() >= minid && mob.getId() <= maxid) {
                count++;
            }
        }
        return count;
    }

    public int countMonsters() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.MONSTER)).size();
    }

    public int countReactors() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.REACTOR)).size();
    }

    public final List<MapObject> getReactors() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.REACTOR));
    }

    public final List<MapObject> getMonsters() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.MONSTER));
    }

    public final List<Reactor> getAllReactors() {
        List<Reactor> list = new LinkedList<>();
        for (MapObject mmo : getReactors()) {
            list.add((Reactor) mmo);
        }

        return list;
    }

    public final List<Monster> getAllMonsters() {
        List<Monster> list = new LinkedList<>();
        for (MapObject mmo : getMonsters()) {
            list.add((Monster) mmo);
        }

        return list;
    }

    public int countItems() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.ITEM)).size();
    }

    public final List<MapObject> getItems() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.ITEM));
    }

    public int countPlayers() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.PLAYER)).size();
    }

    public List<MapObject> getPlayers() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.PLAYER));
    }

    public List<CharacterRef> getAllPlayers() {
        List<CharacterRef> character;
        chrRLock.lock();
        try {
            character = new ArrayList<>(characters);
        } finally {
            chrRLock.unlock();
        }

        return character;
    }

    public Map<Integer, CharacterRef> getMapAllPlayers() {
        Map<Integer, CharacterRef> pchars = new HashMap<>();
        for (CharacterRef chr : this.getAllPlayers()) {
            pchars.put(chr.getId(), chr);
        }

        return pchars;
    }

    public List<CharacterRef> getPlayersInRange(Rectangle box) {
        List<CharacterRef> character = new LinkedList<>();
        chrRLock.lock();
        try {
            for (CharacterRef cr : characters) {
                CharacterRef chr = cr;
                if (box.contains(chr.getPosition())) {
                    character.add(chr);
                }
            }
        } finally {
            chrRLock.unlock();
        }

        return character;
    }

    public int countAlivePlayers() {
        int count = 0;

        for (CharacterRef mc : getAllPlayers()) {
            if (mc.isAlive()) {
                count++;
            }
        }

        return count;
    }

    public int countBosses() {
        int count = 0;

        for (Monster mob : getAllMonsters()) {
            if (mob.isBoss()) {
                count++;
            }
        }

        return count;
    }

    public boolean damageMonster(final CharacterRef chr, final Monster monster, final int damage) {
        if (monster.getId() == MobId.ZAKUM_1) {
            for (MapObject object : chr.getMap().getMapObjects()) {
                Monster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                if (mons != null) {
                    if (mons.getId() >= MobId.ZAKUM_ARM_1 && mons.getId() <= MobId.ZAKUM_ARM_8) {
                        return true;
                    }
                }
            }
        }
        if (monster.isAlive()) {
            boolean killed = monster.damage(chr, damage, false);

            selfDestruction selfDestr = monster.getStats().selfDestruction();
            if (selfDestr != null && selfDestr.getHp() > -1) {// should work ;p
                if (monster.getHp() <= selfDestr.getHp()) {
                    killMonster(monster, chr, true, selfDestr.getAction());
                    return true;
                }
            }
            if (killed) {
                killMonster(monster, chr, true);
            }
            return true;
        }
        return false;
    }

    // 巴洛古(Balrog)讨伐胜利广播
    public void broadcastBalrogVictory(String leaderName) {
        getWorldServer().dropMessage(6,"[远征凯旋] " + leaderName + "的远征队成功讨伐了火焰魔神巴洛古！" + "让我们歌颂这支队伍，他们以" + countAlivePlayers() + "名幸存者的战绩完成了壮举！");
    }

    // 暗黑龙王(Horntail)讨伐胜利广播
    public void broadcastHorntailVictory() {
        getWorldServer().dropMessage(6,"[远征凯旋] 致历经无数次挑战最终征服暗黑龙王的勇士们：" + "谨以此礼赞献给真正的神木村英雄！");
    }

    // 扎昆(Zakum)讨伐胜利广播
    public void broadcastZakumVictory() {
        getWorldServer().dropMessage(6,"[远征凯旋] 长久笼罩天空之城的邪恶之树终于倾倒！" +"致那些历经无数次尝试最终征服扎昆的远征队，胜利属于你们！" +"你们是天空之城真正的传说！");
    }

    // 品克缤(PinkBean)讨伐胜利广播
    public void broadcastPinkBeanVictory(int channelId) {
        getWorldServer().dropMessage(6,"[远征凯旋] 在" + channelId + "频道挑战品克缤的远征队，" +  "以雷霆之势完成了终极讨伐！时间神殿重现璀璨光辉，" + "当英雄们从战场凯旋之时，被夺走的白昼终于归来！"
        );
    }


    private boolean removeKilledMonsterObject(Monster monster) {
        monster.lockMonster();
        try {
            if (monster.getHp() < 0) {
                return false;
            }

            spawnedMonstersOnMap.decrementAndGet();
            removeMapObject(monster);
            monster.disposeMapObject();
            if (monster.hasBossHPBar()) {   // thanks resinate for noticing boss HPbar not clearing after mob defeat in certain scenarios   //感谢resinate注意到在某些情况下暴徒失败后老板HPbar没有清除
                broadcastBossHpMessage(monster, monster.hashCode(), monster.makeBossHPBarPacket(), monster.getPosition());
            }

            return true;
        } finally {
            monster.unlockMonster();
        }
    }

    public void killMonster(final Monster monster, final CharacterRef chr, final boolean withDrops) {
        killMonster(monster, chr, withDrops, 1);
    }

    public void killMonster(final Monster monster, final CharacterRef chr, final boolean withDrops, int animation) {
        if (monster == null) {
            return;
        }

        if (chr == null) {
            if (removeKilledMonsterObject(monster)) {
                monster.dispatchMonsterKilled(false);
                postMapMonsterDeath(monster.getObjectId(), animation);
                monster.aggroSwitchController(null, false);
            }
        } else {
            if (removeKilledMonsterObject(monster)) {
                try {
                    if (monster.getStats().getLevel() >= chr.getLevel() + 30 && !chr.isGM()) {
                        AutobanFactory.GENERAL.alert(chr.unref(), "因击杀超过自身30级的怪物[" + monster.getName() + "]被系统警告");
                    }

                    /*if (chr.getQuest(Quest.getInstance(29400)).getStatus().equals(QuestStatus.Status.STARTED)) {
                     if (chr.getLevel() >= 120 && monster.getStats().getLevel() >= 120) {
                     //FIX MEDAL SHET
                     } else if (monster.getStats().getLevel() >= chr.getLevel()) {
                     }
                     }*/

                    if (monster.getCP() > 0 && chr.getMap().isCPQMap()) {
                        chr.gainCP(monster.getCP());
                    }

                    int buff = monster.getBuffToGive();
                    if (buff > -1) {
                        ItemInformationProvider mii = ItemInformationProvider.getInstance();
                        for (MapObject mmo : this.getPlayers()) {
                            CharacterRef character = CharacterRef.of((Character) mmo);
                            if (character.isAlive()) {
                                BuffEffectData statEffect = mii.getItemEffect(buff);
                                character.sendPacket(PacketCreator.showOwnBuffEffect(buff, 1));
                                broadcastMessage(character, PacketCreator.showBuffEffect(character.getId(), buff, 1), false);
                                statEffect.applyTo(character.unref());
                            }
                        }
                    }

                    if (MobId.isZakumArm(monster.getId())) {
                        boolean makeZakReal = true;
                        Collection<MapObject> objects = getMapObjects();
                        for (MapObject object : objects) {
                            Monster mons = getMonsterByOid(object.getObjectId());
                            if (mons != null) {
                                if (MobId.isZakumArm(mons.getId())) {
                                    makeZakReal = false;
                                    break;
                                }
                            }
                        }
                        if (makeZakReal) {
                            MapleMap map = chr.getMap();

                            for (MapObject object : objects) {
                                Monster mons = map.getMonsterByOid(object.getObjectId());
                                if (mons != null) {
                                    if (mons.getId() == MobId.ZAKUM_1) {
                                        makeMonsterReal(mons);
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    CharacterRef dropOwner = monster.killBy(chr);
                    if (withDrops && !monster.dropsDisabled()) {
                        if (dropOwner == null) {
                            dropOwner = chr;
                        }
                        dropFromMonster(dropOwner, monster, false);
                    }

                    if (monster.hasBossHPBar()) {
                        for (CharacterRef mc : this.getAllPlayers()) {
                            if (mc.getTargetHpBarHash() == monster.hashCode()) {
                                mc.resetPlayerAggro();
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {     // thanks resinate for pointing out a memory leak possibly from an exception thrown
                    monster.dispatchMonsterKilled(true);
                    // KILL_MONSTER 全员值消息投递（原 ranged 直发）：viewer 域按 MapView 成员判
                    // 可见——已知才转发演出包并摘视图，未知丢弃
                    postMapMonsterDeath(monster.getObjectId(), animation);
                }
            }
        }
    }

    public void killFriendlies(Monster mob) {
        this.killMonster(mob, CharacterRef.of((Character) getPlayers().get(0)), false);
    }

    public void killMonster(int mobId) {
        CharacterRef chr = null;
        List<MapObject> players = getPlayers();
        if (!players.isEmpty()) {
            chr = CharacterRef.of((Character) players.get(0));
        }
        List<Monster> mobList = getAllMonsters();
        for (Monster mob : mobList) {
            if (mob.getId() == mobId) {
                this.killMonster(mob, chr, false);
            }
        }
    }

    public void killMonsterWithDrops(int mobId) {
        Map<Integer, CharacterRef> mapChars = this.getMapPlayers();

        if (!mapChars.isEmpty()) {
            CharacterRef defaultChr = mapChars.entrySet().iterator().next().getValue();
            List<Monster> mobList = getAllMonsters();

            for (Monster mob : mobList) {
                if (mob.getId() == mobId) {
                    CharacterRef chr = mapChars.get(mob.getHighestDamagerId());
                    if (chr == null) {
                        chr = defaultChr;
                    }

                    this.killMonster(mob, chr, true);
                }
            }
        }
    }

    public void softKillAllMonsters() {
        closeMapSpawnPoints();

        for (MapObject monstermo : getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.MONSTER))) {
            Monster monster = (Monster) monstermo;
            if (monster.getStats().isFriendly()) {
                continue;
            }

            if (removeKilledMonsterObject(monster)) {
                monster.dispatchMonsterKilled(false);
            }
        }
    }

    public void killAllMonstersNotFriendly() {
        closeMapSpawnPoints();

        for (MapObject monstermo : getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.MONSTER))) {
            Monster monster = (Monster) monstermo;
            if (monster.getStats().isFriendly()) {
                continue;
            }

            killMonster(monster, null, false, 1);
        }
    }

    public void killAllMonsters() {
        closeMapSpawnPoints();

        for (MapObject monstermo : getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.MONSTER))) {
            Monster monster = (Monster) monstermo;

            killMonster(monster, null, false, 1);
        }
    }

    public final void destroyReactors(final int first, final int last) {
        List<Reactor> toDestroy = new ArrayList<>();
        List<MapObject> reactors = getReactors();

        for (MapObject obj : reactors) {
            Reactor mr = (Reactor) obj;
            if (mr.getId() >= first && mr.getId() <= last) {
                toDestroy.add(mr);
            }
        }

        for (Reactor mr : toDestroy) {
            destroyReactor(mr.getObjectId());
        }
    }

    public void destroyReactor(int oid) {
        final Reactor reactor = getReactorByOid(oid);

        if (reactor != null) {
            if (reactor.destroy()) {
                removeMapObject(reactor);
            }
        }
    }

    public void resetReactors() {
        List<Reactor> list = new ArrayList<>();

        objectRLock.lock();
        try {
            for (MapObject o : mapobjects.values()) {
                if (o.getType() == MapObjectType.REACTOR) {
                    final Reactor r = ((Reactor) o);
                    list.add(r);
                }
            }
        } finally {
            objectRLock.unlock();
        }

        resetReactors(list);
    }

    public final void resetReactors(List<Reactor> list) {
        for (Reactor r : list) {
            if (r.forceDelayedRespawn()) {  // thanks Conrad for suggesting reactor with delay respawning immediately
                continue;
            }

            r.lockReactor();
            try {
                r.resetReactorActions(0);
                r.setAlive(true);
                broadcastMessage(PacketCreator.triggerReactor(r, 0));
            } finally {
                r.unlockReactor();
            }
        }
    }

    public void shuffleReactors() {
        List<Point> points = new ArrayList<>();
        objectRLock.lock();
        try {
            for (MapObject o : mapobjects.values()) {
                if (o.getType() == MapObjectType.REACTOR) {
                    points.add(o.getPosition());
                }
            }
            Collections.shuffle(points);
            for (MapObject o : mapobjects.values()) {
                if (o.getType() == MapObjectType.REACTOR) {
                    o.setPosition(points.remove(points.size() - 1));
                }
            }
        } finally {
            objectRLock.unlock();
        }
    }

    public final void shuffleReactors(int first, int last) {
        List<Point> points = new ArrayList<>();
        List<MapObject> reactors = getReactors();
        List<MapObject> targets = new LinkedList<>();

        for (MapObject obj : reactors) {
            Reactor mr = (Reactor) obj;
            if (mr.getId() >= first && mr.getId() <= last) {
                points.add(mr.getPosition());
                targets.add(obj);
            }
        }
        Collections.shuffle(points);
        for (MapObject obj : targets) {
            Reactor mr = (Reactor) obj;
            mr.setPosition(points.remove(points.size() - 1));
        }
    }

    public final void shuffleReactors(List<Object> list) {
        List<Point> points = new ArrayList<>();
        List<MapObject> listObjects = new ArrayList<>();
        List<MapObject> targets = new LinkedList<>();

        objectRLock.lock();
        try {
            for (Object ob : list) {
                if (ob instanceof MapObject mmo) {

                    if (mapobjects.containsValue(mmo) && mmo.getType() == MapObjectType.REACTOR) {
                        listObjects.add(mmo);
                    }
                }
            }
        } finally {
            objectRLock.unlock();
        }

        for (MapObject obj : listObjects) {
            Reactor mr = (Reactor) obj;

            points.add(mr.getPosition());
            targets.add(obj);
        }
        Collections.shuffle(points);
        for (MapObject obj : targets) {
            Reactor mr = (Reactor) obj;
            mr.setPosition(points.remove(points.size() - 1));
        }
    }

    private Map<Integer, MapObject> getCopyMapObjects() {
        objectRLock.lock();
        try {
            return new HashMap<>(mapobjects);
        } finally {
            objectRLock.unlock();
        }
    }

    public List<MapObject> getMapObjects() {
        objectRLock.lock();
        try {
            return new LinkedList<>(mapobjects.values());
        } finally {
            objectRLock.unlock();
        }
    }

    public NPC getNPCById(int id) {
        for (MapObject obj : getMapObjects()) {
            if (obj.getType() == MapObjectType.NPC) {
                NPC npc = (NPC) obj;
                if (npc.getId() == id) {
                    return npc;
                }
            }
        }

        return null;
    }

    public boolean containsNPC(int npcid) {
        objectRLock.lock();
        try {
            for (MapObject obj : mapobjects.values()) {
                if (obj.getType() == MapObjectType.NPC) {
                    if (((NPC) obj).getId() == npcid) {
                        return true;
                    }
                }
            }
        } finally {
            objectRLock.unlock();
        }
        return false;
    }

    public void destroyNPC(int npcid) {     // assumption: there's at most one of the same NPC in a map.
        List<MapObject> npcs = getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.NPC));

        chrRLock.lock();
        objectWLock.lock();
        try {
            for (MapObject obj : npcs) {
                if (((NPC) obj).getId() == npcid) {
                    broadcastMessage(PacketCreator.removeNPCController(obj.getObjectId()));
                    broadcastMessage(PacketCreator.removeNPC(obj.getObjectId()));

                    this.mapobjects.remove(obj.getObjectId());
                }
            }
        } finally {
            objectWLock.unlock();
            chrRLock.unlock();
        }
    }

    public MapObject getMapObject(int oid) {
        objectRLock.lock();
        try {
            return mapobjects.get(oid);
        } finally {
            objectRLock.unlock();
        }
    }

    /**
     * returns a monster with the given oid, if no such monster exists returns
     * null
     *
     * @param oid
     * @return
     */
    public Monster getMonsterByOid(int oid) {
        MapObject mmo = getMapObject(oid);
        return (mmo != null && mmo.getType() == MapObjectType.MONSTER) ? (Monster) mmo : null;
    }

    public Reactor getReactorByOid(int oid) {
        MapObject mmo = getMapObject(oid);
        return (mmo != null && mmo.getType() == MapObjectType.REACTOR) ? (Reactor) mmo : null;
    }

    public Reactor getReactorById(int Id) {
        objectRLock.lock();
        try {
            for (MapObject obj : mapobjects.values()) {
                if (obj.getType() == MapObjectType.REACTOR) {
                    if (((Reactor) obj).getId() == Id) {
                        return (Reactor) obj;
                    }
                }
            }
            return null;
        } finally {
            objectRLock.unlock();
        }
    }

    public List<Reactor> getReactorsByIdRange(final int first, final int last) {
        List<Reactor> list = new LinkedList<>();

        objectRLock.lock();
        try {
            for (MapObject obj : mapobjects.values()) {
                if (obj.getType() == MapObjectType.REACTOR) {
                    Reactor mr = (Reactor) obj;

                    if (mr.getId() >= first && mr.getId() <= last) {
                        list.add(mr);
                    }
                }
            }

            return list;
        } finally {
            objectRLock.unlock();
        }
    }

    public Reactor getReactorByName(String name) {
        objectRLock.lock();
        try {
            for (MapObject obj : mapobjects.values()) {
                if (obj.getType() == MapObjectType.REACTOR) {
                    if (((Reactor) obj).getName().equals(name)) {
                        return (Reactor) obj;
                    }
                }
            }
        } finally {
            objectRLock.unlock();
        }
        return null;
    }

    public void spawnMonsterOnGroundBelow(int id, int x, int y) {
        Monster mob = LifeFactory.getMonster(id);
        spawnMonsterOnGroundBelow(mob, new Point(x, y));
    }

    public void spawnMonsterOnGroundBelow(Monster mob, Point pos) {
        Point spos = new Point(pos.x, pos.y - 1);
        spos = calcPointBelow(spos);
        spos.y--;
        mob.setPosition(spos);
        spawnMonster(mob);
    }

    public void spawnCPQMonster(Monster mob, Point pos, int team) {
        Point spos = new Point(pos.x, pos.y - 1);
        spos = calcPointBelow(spos);
        spos.y--;
        mob.setPosition(spos);
        mob.setTeam(team);
        spawnMonster(mob);
    }

    private void monsterItemDrop(final Monster m, long delay) {
        m.dropFromFriendlyMonster(delay);
    }

    public void spawnFakeMonsterOnGroundBelow(Monster mob, Point pos) {
        Point spos = getGroundBelow(pos);
        mob.setPosition(spos);
        spawnFakeMonster(mob);
    }

    public Point getGroundBelow(Point pos) {
        Point spos = new Point(pos.x, pos.y - 14); // Using -14 fixes spawning pets causing a lot of issues.
        spos = calcPointBelow(spos);
        spos.y--;//shouldn't be null!
        return spos;
    }

    public Point getPointBelow(Point pos) {
        return calcPointBelow(pos);
    }

    public void spawnRevives(final Monster monster) {
        monster.setMap(this);
        if (getEventInstance() != null) {
            getEventInstance().registerMonster(monster);
        }

        spawnAndPostMonster(monster, false, 0, false);

        monster.aggroUpdateController();
        updateBossSpawn(monster);

        spawnedMonstersOnMap.incrementAndGet();
        addSelfDestructive(monster);
        applyRemoveAfter(monster);
    }

    private void applyRemoveAfter(final Monster monster) {
        final selfDestruction selfDestruction = monster.getStats().selfDestruction();
        if (monster.getStats().removeAfter() > 0 || selfDestruction != null && selfDestruction.getHp() < 0) {
            Runnable removeAfterAction;

            if (selfDestruction == null) {
                removeAfterAction = () -> killMonster(monster, null, false);

                registerMapSchedule(removeAfterAction, SECONDS.toMillis(monster.getStats().removeAfter()));
            } else {
                removeAfterAction = () -> killMonster(monster, null, false, selfDestruction.getAction());

                registerMapSchedule(removeAfterAction, SECONDS.toMillis(selfDestruction.removeAfter()));
            }

            monster.pushRemoveAfterAction(removeAfterAction);
        }
    }

    public void dismissRemoveAfter(final Monster monster) {
        Runnable removeAfterAction = monster.popRemoveAfterAction();
        if (removeAfterAction != null) {
            OverallService service = (OverallService) this.getChannelServer().getServiceAccess(ChannelServices.OVERALL);
            service.forceRunOverallAction(st.mapid(), removeAfterAction);
        }
    }

    private List<SpawnPoint> getMonsterSpawn() {
        synchronized (monsterSpawn) {
            return new ArrayList<>(monsterSpawn);
        }
    }

    private List<SpawnPoint> getAllMonsterSpawn() {
        synchronized (allMonsterSpawn) {
            return new ArrayList<>(allMonsterSpawn);
        }
    }

    public void spawnAllMonsterIdFromMapSpawnList(int id) {
        spawnAllMonsterIdFromMapSpawnList(id, 1, false);
    }

    public void spawnAllMonsterIdFromMapSpawnList(int id, int difficulty, boolean isPq) {
        for (SpawnPoint sp : getAllMonsterSpawn()) {
            if (sp.getMonsterId() == id && sp.shouldForceSpawn()) {
                spawnMonster(sp.getMonster(), difficulty, isPq);
            }
        }
    }

    public void spawnAllMonstersFromMapSpawnList() {
        spawnAllMonstersFromMapSpawnList(1, false);
    }

    public void spawnAllMonstersFromMapSpawnList(int difficulty, boolean isPq) {
        for (SpawnPoint sp : getAllMonsterSpawn()) {
            spawnMonster(sp.getMonster(), difficulty, isPq);
        }
    }

    public void spawnMonster(final Monster monster) {
        spawnMonster(monster, 1, false);
    }

    public void spawnMonster(final Monster monster, int difficulty, boolean isPq) {
        if (st.mobCapacity() != -1 && st.mobCapacity() == spawnedMonstersOnMap.get()) {
            return;//PyPQ
        }

        monster.changeDifficulty(difficulty, isPq);

        monster.setMap(this);
        if (getEventInstance() != null) {
            getEventInstance().registerMonster(monster);
        }

        spawnAndPostMonster(monster, true, 0, false);

        monster.aggroUpdateController();
        updateBossSpawn(monster);

        if ((monster.getTeam() == 1 || monster.getTeam() == 0) && (isCPQMap() || isCPQMap2())) {
            List<MCSkill> teamS = null;
            if (monster.getTeam() == 0) {
                teamS = redTeamBuffs;
            } else if (monster.getTeam() == 1) {
                teamS = blueTeamBuffs;
            }
            if (teamS != null) {
                for (MCSkill skil : teamS) {
                    if (skil != null) {
                        skil.getSkill().applyEffect(null, monster, false, null);
                    }
                }
            }
        }

        if (monster.getDropPeriodTime() > 0) { //9300102 - Watchhog, 9300061 - Moon Bunny (HPQ), 9300093 - Tylus    //9300102-护卫用小浣猪，9300061-月妙（HPQ），9300093-冒牌泰勒斯
            if (monster.getId() == MobId.WATCH_HOG) {
                monsterItemDrop(monster, monster.getDropPeriodTime());
            } else if (monster.getId() == MobId.MOON_BUNNY) {
                monsterItemDrop(monster, monster.getDropPeriodTime() / 3);
            } else if (monster.getId() == MobId.TYLUS) {
                monsterItemDrop(monster, monster.getDropPeriodTime());
            } else if (monster.getId() == MobId.GIANT_SNOWMAN_LV5_EASY || monster.getId() == MobId.GIANT_SNOWMAN_LV5_MEDIUM || monster.getId() == MobId.GIANT_SNOWMAN_LV5_HARD) {
                monsterItemDrop(monster, monster.getDropPeriodTime());
            } else {
                log.error("[异常刷怪] 检测到未配置定时刷新的怪物: ID={}", monster.getId());
            }
        }

        spawnedMonstersOnMap.incrementAndGet();
        addSelfDestructive(monster);
        applyRemoveAfter(monster);  // thanks LightRyuzaki for pointing issues with spawned CWKPQ mobs not applying this
    }

    public void spawnDojoMonster(final Monster monster) {
        Point[] pts = {new Point(140, 0), new Point(190, 7), new Point(187, 7)};
        spawnMonsterWithEffect(monster, 15, pts[Randomizer.nextInt(3)]);
    }

    public void spawnMonsterWithEffect(final Monster monster, final int effect, Point pos) {
        monster.setMap(this);
        Point spos = new Point(pos.x, pos.y - 1);
        spos = calcPointBelow(spos);
        if (spos == null) {
            return;
        }

        if (getEventInstance() != null) {
            getEventInstance().registerMonster(monster);
        }

        spos.y--;
        monster.setPosition(spos);
        monster.setSpawnEffect(effect);

        spawnAndPostMonster(monster, true, effect, false);

        monster.aggroUpdateController();
        updateBossSpawn(monster);

        spawnedMonstersOnMap.incrementAndGet();
        addSelfDestructive(monster);
        applyRemoveAfter(monster);
    }

    public void spawnFakeMonster(final Monster monster) {
        monster.setMap(this);
        monster.setFake(true);
        spawnAndPostMonster(monster, false, 0, true);

        spawnedMonstersOnMap.incrementAndGet();
        addSelfDestructive(monster);
    }

    public void makeMonsterReal(final Monster monster) {
        monster.setFake(false);
        broadcastMessage(PacketCreator.makeMonsterReal(monster));
        monster.aggroUpdateController();
        updateBossSpawn(monster);
    }

    public void spawnReactor(final Reactor reactor) {
        reactor.setMap(this);
        spawnAndPostMapObject(reactor, () -> List.of(reactor.makeSpawnData()));
    }

    public void spawnDoor(final DoorObject door) {
        // 遗留：包 viewer 相关（partyPortal/自身 mapId 门），无法值核——保留内联直发（含 inRange
        // 收集的 getPosition 读），door/MapItem-poke 债清偿时随迁
        List<CharacterRef> inRangeCharacters = new LinkedList<>();
        int curOID = getUsableOID();

        chrRLock.lock();
        objectWLock.lock();
        try {
            door.setObjectId(curOID);
            this.mapobjects.put(curOID, door);
            for (CharacterRef cr : characters) {
                if (cr.getMapId() == door.getFrom().getId()
                        && cr.getPosition().distanceSq(door.getPosition()) <= getRangedDistance()) {
                    inRangeCharacters.add(cr);
                }
            }
        } finally {
            objectWLock.unlock();
            chrRLock.unlock();
        }

        final MapView.Entry viewEntry = viewEntry(door);
        for (CharacterRef chr : inRangeCharacters) {
            door.sendSpawnData(chr.getClient(), false);
            chr.post(new MapObjectsViewMessage(getId(), List.of(viewEntry), List.of()));
        }
    }

    public Portal getDoorPortal(int doorid) {
        Portal doorPortal = portals.get(0x80 + doorid);
        if (doorPortal == null) {
            log.warn("[传动点] 地图 {} (ID:{}) 不存在传送门ID为 {} 的入口", st.mapName(), st.mapid(), doorid);
            return portals.get(0x80);
        }

        return doorPortal;
    }

    public void spawnSummon(final Summon summon) {
        spawnAndPostMapObject(summon, () -> List.of(PacketCreator.spawnSummon(summon, true)));
    }

    /**
     * summon 投放（owner 排除变体，原 spawnSummon 的 enterMap 窗口内形态）：视野范围广播
     * 对 owner 的位置读会在其 strict 窗口开着时触发 canary——owner 的投递（可见集 + 同形包）
     * 由 caller 以本体直调完成（CharacterMap.enterMap）。
     */
    void spawnSummonExcludeOwner(final Summon summon, final CharacterRef owner) {
        List<CharacterRef> inRangeCharacters = new LinkedList<>();
        int curOID = getUsableOID();

        chrRLock.lock();
        objectWLock.lock();
        try {
            summon.setObjectId(curOID);
            this.mapobjects.put(curOID, summon);
            for (CharacterRef cr : characters) {
                if (cr != owner) {
                    if (cr.getPosition().distanceSq(summon.getPosition()) <= getRangedDistance()) {
                        inRangeCharacters.add(cr);
                    }
                }
            }
        } finally {
            objectWLock.unlock();
            chrRLock.unlock();
        }

        final MapView.Entry viewEntry = viewEntry(summon);
        for (CharacterRef chr : inRangeCharacters) {
            chr.sendPacket(PacketCreator.spawnSummon(summon, true));   // 与原 packetbakery 同包形
            chr.post(new MapObjectsViewMessage(getId(), List.of(viewEntry), List.of()));
        }
    }

    public void spawnMist(final Mist mist, final int duration, boolean poison, boolean fake, boolean recovery) {
        addMapObject(mist);
        broadcastMessage(fake ? mist.makeFakeSpawnData(30) : mist.makeSpawnData());
        TimerManager tMan = TimerManager.getInstance();
        final ScheduledFuture<?> poisonSchedule;
        if (poison) {
            Runnable poisonTask = () -> {
                List<MapObject> affectedMonsters = getMapObjectsInBox(mist.getBox(), Collections.singletonList(MapObjectType.MONSTER));
                for (MapObject mo : affectedMonsters) {
                    if (mist.makeChanceResult()) {
                        MonsterStatusEffect poisonEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), mist.getSourceSkill(), null, false);
                        ((Monster) mo).applyStatus(mist.getOwner().ref(), poisonEffect, true, duration);
                    }
                }
            };
            poisonSchedule = tMan.register(poisonTask, 2000, 2500);
        } else if (recovery) {
            Runnable poisonTask = () -> {
                List<MapObject> players = getMapObjectsInBox(mist.getBox(), Collections.singletonList(MapObjectType.PLAYER));
                for (MapObject mo : players) {
                    if (mist.makeChanceResult()) {
                        CharacterRef chr = CharacterRef.of((Character) mo);
                        if (mist.getOwner().getId() == chr.getId() || mist.getOwner().getParty() != null && mist.getOwner().getParty().containsMembers(chr.getMPC())) {
                            chr.addMP(mist.getSourceSkill().getEffect(chr.getSkillLevel(mist.getSourceSkill().getId())).getX() * chr.getMp() / 100);
                        }
                    }
                }
            };
            poisonSchedule = tMan.register(poisonTask, 2000, 2500);
        } else {
            poisonSchedule = null;
        }

        Runnable mistSchedule = () -> {
            removeMapObject(mist);
            if (poisonSchedule != null) {
                poisonSchedule.cancel(false);
            }
            broadcastMessage(mist.makeDestroyData());
        };

        MobMistService service = (MobMistService) this.getChannelServer().getServiceAccess(ChannelServices.MOB_MIST);
        service.registerMobMistCancelAction(st.mapid(), mistSchedule, duration);
    }

    public void spawnKite(final Kite kite) {
        addMapObject(kite);
        broadcastMessage(kite.makeSpawnData());

        Runnable expireKite = () -> {
            removeMapObject(kite);
            broadcastMessage(kite.makeDestroyData());
        };

        getWorldServer().registerTimedMapObject(expireKite, GameConfig.getServerLong("kite_expire_time"));
    }

    public final void spawnItemDrop(final MapObject dropper, final CharacterRef owner, final ItemSlot item, Point pos, final boolean ffaDrop, final boolean playerDrop) {
        spawnItemDrop(dropper, owner, item, pos, (byte) (ffaDrop ? 2 : 0), playerDrop);
    }

    public final void spawnItemDrop(final MapObject dropper, final CharacterRef owner, final ItemSlot item, Point pos, final byte dropType, final boolean playerDrop) {
        if (FieldLimit.DROP_LIMIT.check(this.getFieldLimit())) { // thanks Conrad for noticing some maps shouldn't have loots available
            this.disappearingItemDrop(dropper, owner, item, pos);
            return;
        }

        final Point droppos = calcDropPos(pos, pos);
        final MapItem mdrop = new MapItem(item, droppos, dropper, owner, dropType, playerDrop);
        mdrop.setDropTime(Server.getInstance().getCurrentTime());
        // 值消息投递（原 bakery 无条件直发；viewer 过滤 needQuestItem(-1,·) 恒真 = 等价）
        spawnAndAddRangedMapObject(mdrop, dropper.getObjectId(), dropper.getPosition(), droppos, (byte) 1);

        mdrop.lockItem();
        try {
            broadcastItemDropMessage(mdrop, dropper.getPosition(), droppos, (byte) 0);
        } finally {
            mdrop.unlockItem();
        }

        instantiateItemDrop(mdrop);
        activateItemReactors(mdrop, owner);
    }

    public final void spawnItemDropList(List<Integer> list, final MapObject dropper, final CharacterRef owner, Point pos) {
        spawnItemDropList(list, 1, 1, dropper, owner, pos, true, false);
    }

    public final void spawnItemDropList(List<Integer> list, int minCopies, int maxCopies, final MapObject dropper, final CharacterRef owner, Point pos) {
        spawnItemDropList(list, minCopies, maxCopies, dropper, owner, pos, true, false);
    }

    // spawns item instances of all defined item ids on a list
    public final void spawnItemDropList(List<Integer> list, int minCopies, int maxCopies, final MapObject dropper, final CharacterRef owner, Point pos, final boolean ffaDrop, final boolean playerDrop) {
        int copies = (maxCopies - minCopies) + 1;
        if (copies < 1) {
            return;
        }

        Collections.shuffle(list);

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Random rnd = new Random();

        final Point dropPos = new Point(pos);
        dropPos.x -= (12 * list.size());

        for (Integer integer : list) {
            if (integer == 0) {
                spawnMesoDrop(owner != null ? NumberTool.floatToInt(10 * owner.getMesoRate()) : 10, calcDropPos(dropPos, pos), dropper, owner, playerDrop, (byte) (ffaDrop ? 2 : 0));
            } else {
                final ItemSlot drop;
                int randomedId = integer;

                if (ItemConstants.getInventoryType(randomedId) != InventoryType.EQUIP) {
                    drop = new ItemSlot(randomedId, (short) 0, (short) (rnd.nextInt(copies) + minCopies));
                } else {
                    ItemSlot equipDrop = ii.getEquipById(randomedId);
                    ii.randomizeStats(equipDrop.getEquipInfo());
                    drop = equipDrop;
                }

                spawnItemDrop(dropper, owner, drop, calcDropPos(dropPos, pos), ffaDrop, playerDrop);
            }

            dropPos.x += 25;
        }
    }

    private void registerMapSchedule(Runnable r, long delay) {
        OverallService service = (OverallService) this.getChannelServer().getServiceAccess(ChannelServices.OVERALL);
        service.registerOverallAction(st.mapid(), r, delay);
    }

    private void activateItemReactors(final MapItem drop, final CharacterRef owner) {
        final ItemSlot item = drop.getItem();

        for (final MapObject o : getReactors()) {
            final Reactor react = (Reactor) o;

            if (react.getReactorType() == 100) {
                if (react.getReactItem(react.getEventState()).getLeft() == item.getItemId() && react.getReactItem(react.getEventState()).getRight() == item.getQuantity()) {

                    if (react.getArea().contains(drop.getPosition())) {
                        // Client 需要时从 ref 拿（reactor 图才有此分支，惰性解析）
                        registerMapSchedule(new ActivateItemReactor(drop, react, owner.getClient()), 5000);
                        break;
                    }
                }
            }
        }
    }

    public void searchItemReactors(final Reactor react) {
        if (react.getReactorType() == 100) {
            Pair<Integer, Integer> reactProp = react.getReactItem(react.getEventState());
            int reactItem = reactProp.getLeft(), reactQty = reactProp.getRight();
            Rectangle reactArea = react.getArea();

            List<MapItem> list;
            objectRLock.lock();
            try {
                list = new ArrayList<>(droppedItems.keySet());
            } finally {
                objectRLock.unlock();
            }

            for (final MapItem drop : list) {
                drop.lockItem();
                try {
                    if (!drop.isPickedUp()) {
                        final ItemSlot item = drop.getItem();

                        if (item != null && reactItem == item.getItemId() && reactQty == item.getQuantity()) {
                            if (reactArea.contains(drop.getPosition())) {
                                Client owner = drop.getOwnerClient();
                                if (owner != null) {
                                    registerMapSchedule(new ActivateItemReactor(drop, react, owner), 5000);
                                }
                            }
                        }
                    }
                } finally {
                    drop.unlockItem();
                }
            }
        }
    }

    public void changeEnvironment(String mapObj, int newState) {
        broadcastMessage(PacketCreator.environmentChange(mapObj, newState));
    }

    public void startMapEffect(String msg, int itemId) {
        startMapEffect(msg, itemId, 30000);
    }

    public void startMapEffect(String msg, int itemId, long time) {
        if (mapEffect != null) {
            return;
        }
        mapEffect = new MapEffect(msg, itemId);
        broadcastMessage(mapEffect.makeStartData());

        Runnable r = () -> {
            broadcastMessage(mapEffect.makeDestroyData());
            mapEffect = null;
        };

        registerMapSchedule(r, time);
    }

    public CharacterRef getAnyCharacterFromParty(int partyid) {
        for (CharacterRef chr : this.getAllPlayers()) {
            if (chr.getPartyId() == partyid) {
                return chr;
            }
        }

        return null;
    }

    private void addPartyMemberInternal(CharacterRef chr, int partyid) {
        if (partyid == -1) {
            return;
        }

        Set<Integer> partyEntry = mapParty.get(partyid);
        if (partyEntry == null) {
            partyEntry = new LinkedHashSet<>();
            partyEntry.add(chr.getId());

            mapParty.put(partyid, partyEntry);
        } else {
            partyEntry.add(chr.getId());
        }
    }

    private void removePartyMemberInternal(int cid, int partyid) {
        if (partyid == -1) {
            return;
        }

        Set<Integer> partyEntry = mapParty.get(partyid);
        if (partyEntry != null) {
            if (partyEntry.size() > 1) {
                partyEntry.remove(cid);
            } else {
                mapParty.remove(partyid);
            }
        }
    }

    public void addPartyMember(CharacterRef chr, int partyid) {
        chrWLock.lock();
        try {
            addPartyMemberInternal(chr, partyid);
        } finally {
            chrWLock.unlock();
        }
    }

    public void removePartyMember(CharacterRef chr, int partyid) {
        chrWLock.lock();
        try {
            removePartyMemberInternal(chr.getId(), partyid);
        } finally {
            chrWLock.unlock();
        }
    }

    public void removeParty(int partyid) {
        chrWLock.lock();
        try {
            mapParty.remove(partyid);
        } finally {
            chrWLock.unlock();
        }
    }

    /**
     * 玩家进图登记。{@code summonedPets} 为进图者宠物召唤快照——由调用方在<b>其 strand 上</b>
     * 经 {@code chr.getPets().getSummonedPets()}（List.copyOf）采集后随边界传入（doc/13 §5.2
     * 快照过界）：本方法未来在 map shim 任务体（池线程）执行，此处禁止对 player actor 的
     * 跨线程读/阻塞回询（addPlayer 是 player strand `run` 的缝合点，回询 = 环死锁）。
     */
    /**
     * 进图登记（纯任务体——shim supply 包装在 MapleMapRef.registerPlayer）：图侧登记 + 他人广播。
     * 缝合点审计通过：无脚本入口/无 pet 阻塞回询/无自发包（doc/13 §4）。
     *
     * @return firstEnter（chrSize==1，登记动作的产物）：finishEnter 的 onFirstUserEnter 触发条件
     */
    // ── CharacterRef 面向重载（legacy 外部调用方；转换后委托 ref 版本）──

    // ── 翻转后的 ref 版本（原实现）──

    // ── Character 面向重载（legacy 外部调用方；CharacterRef.of 转换，null 透传）──

    public void broadcastMessage(Character source, Packet packet, boolean repeatToSource) {
        broadcastMessage(CharacterRef.of(source), packet, repeatToSource);
    }

    public void broadcastMessage(Character source, Packet packet, boolean repeatToSource, boolean ranged) {
        broadcastMessage(CharacterRef.of(source), packet, repeatToSource, ranged);
    }

    public void broadcastGMMessage(Character source, Packet packet, boolean repeatToSource) {
        broadcastGMMessage(CharacterRef.of(source), packet, repeatToSource);
    }

    public void broadcastNONGMMessage(Character source, Packet packet, boolean repeatToSource) {
        broadcastNONGMMessage(CharacterRef.of(source), packet, repeatToSource);
    }

    public void broadcastPacket(Character source, Packet packet) {
        broadcastPacket(CharacterRef.of(source), packet);
    }

    public void broadcastUpdateCharLookMessage(Character source, Character player) {
        broadcastUpdateCharLookMessage(CharacterRef.of(source), CharacterRef.of(player));
    }

    public void broadcastSpawnPlayerMapObjectMessage(Character source, Character player, boolean enteringField) {
        broadcastSpawnPlayerMapObjectMessage(CharacterRef.of(source), CharacterRef.of(player), enteringField);
    }

    public void addPlayerPuppet(Character player) {
        addPlayerPuppet(CharacterRef.of(player));
    }

    public void removePlayerPuppet(Character player) {
        removePlayerPuppet(CharacterRef.of(player));
    }

    public void removePlayer(Character chr) {
        removePlayer(chr.removeFacts());
    }

    public List<MapItem> updatePlayerItemDropsToParty(int partyid, int charid, List<Character> partyMembers, Character partyLeaver) {
        return updatePlayerItemDropsToParty(partyid, charid,
                partyMembers.stream().map(CharacterRef::of).toList(), CharacterRef.of(partyLeaver));
    }

    public void updatePartyItemDropsToNewcomer(Character newcomer, List<MapItem> partyItems) {
        updatePartyItemDropsToNewcomer(CharacterRef.of(newcomer), partyItems);
    }

    public boolean registerPlayer(final CharacterRef cr, final List<Pet> summonedPets, final Party party) {
        final CharacterRef chr = cr;
        cleanupGhostPlayers();   // 被动清理：进图前先清掉图上"已断线未正常移除"的幽灵玩家，避免其他人仍看到他

        int chrSize;
        chrWLock.lock();
        try {
            characters.add(cr);
            chrSize = characters.size();

            if (party != null && party.getMemberById(chr.getId()) != null) {
                addPartyMemberInternal(chr, party.getId());
            }
            itemMonitorTimeout = 1;
        } finally {
            chrWLock.unlock();
        }

        final boolean firstEnter = chrSize == 1;
        if (firstEnter && !hasItemMonitor()) {
            startItemMonitor();
            aggroMonitor.startAggroCoordinator();
        }

        // 他人流（对进图者不可见）：自 addPlayer 尾段前移，内部相对顺序保持；单机 probe 不可观测。
        // （isHidden 分支按"单机无 GM"裁定删除，视为恒 false——快照化后任务体对入场者零 Character 访问）
        broadcastSpawnPlayerMapObjectMessage(chr, chr, true);
        return firstEnter;
    }


    /** 船停靠运行时态（ref 通道用；boat 能力本身是静态——caller 先查 statics().boat()） */
    boolean isBoatDocked() {
        return docked;
    }

    /**
     * 入场对象投放（原 finishEnter 内联段；map actor 任务体——编舞最后一个原位 helper
     * 的 shim 化，§5.4 豁免收口）：非视野型对象 spawn 流 + 陈旧 summon 注册表清理 +
     * 视野范围内对象 spawn 流。参数全快照（落点/本体 id/自有 summon 集）；spawn 直发段
     * 经 {@link CharacterRef#postLegacyPacket} 回 strand（窗口内不得经 ref 取 client），
     * 包序 = 收集序（非视野型在前、视野型在后，与原两段循环一致），整体落到收件 strand
     * 队列尾；返回视野新增集，由调用方回放到本体可见集（wire 无差：可见集为服务端簿记）。
     */
    /**
     * 入场 placement 产物（值 seed）：对象条目 + 怪物值视图（授控帧数据源；receiver 在
     * 编舞内先 putMonster 再处理随后的 toElect 授控消息，strand FIFO 序天然成立）。
     */
    public record EnterPlacement(List<MapView.Entry> entries, List<MapView.MonsterView> monsterViews) {
        public EnterPlacement {
            entries = List.copyOf(entries);
            monsterViews = List.copyOf(monsterViews);
        }
    }

    EnterPlacement sendObjectPlacement(CharacterRef chr, Point pos, int cid, Collection<Summon> ownedSummons) {
        Collection<MapObject> objects;

        objectRLock.lock();
        try {
            objects = new ArrayList<>(mapobjects.values());
        } finally {
            objectRLock.unlock();
        }

        List<MapObject> spawns = new ArrayList<>();
        List<MapView.Entry> viewAdds = new ArrayList<>();
        for (MapObject o : objects) {
            if (isNonRangedType(o.getType())) {
                spawns.add(o);
                viewAdds.add(viewEntry(o));
            } else if (o.getType() == MapObjectType.SUMMON) {
                Summon summon = (Summon) o;
                if (summon.getOwner().getId() == cid && !ownedSummons.contains(summon)) {
                    objectWLock.lock();
                    try {
                        mapobjects.remove(o.getObjectId());
                    } finally {
                        objectWLock.unlock();
                    }
                }
            }
        }

        List<MapObject> addRefs = new ArrayList<>();
        List<Monster> toElect = new ArrayList<>();
        List<Monster> inRangeMonsters = new ArrayList<>();
        for (MapObject o : getMapObjectsInRange(pos, getRangedDistance(), rangedMapobjectTypes)) {
            if (o.getType() == MapObjectType.REACTOR) {
                if (((Reactor) o).isAlive()) {
                    spawns.add(o);
                    addRefs.add(o);
                    viewAdds.add(viewEntry(o));
                }
            } else {
                spawns.add(o);
                addRefs.add(o);
                viewAdds.add(viewEntry(o));

                if (o.getType() == MapObjectType.MONSTER) {
                    inRangeMonsters.add((Monster) o);
                    toElect.add((Monster) o);
                }
            }
        }

        chr.postLegacyPacket(getId(), "sendObjectPlacement", client -> {
            for (MapObject o : spawns) {
                o.sendSpawnData(client);
            }
        });
        // 授控延后于 spawn 包入队执行：plain SPAWN_MONSTER 会重置 client 控制位，grant
        // 落尾才生效（循环内即时入队会先于批次 spawn 到达 = 登录后怪不受控回归根因之一）
        for (Monster m : toElect) {
            m.aggroUpdateController();
        }
        // 怪物值视图建在选举之后：快照真实反映换届结果（controlled 语义成立）
        List<MapView.MonsterView> monsterViews = new ArrayList<>(inRangeMonsters.size());
        for (Monster m : inRangeMonsters) {
            monsterViews.add(MapView.MonsterView.of(m));
        }
        return new EnterPlacement(viewAdds, monsterViews);
    }

    /** 入场注册表登记（原 finishEnter 段；oid 快照——cr.getObjectId() 本身是 ref 直调）+ 商店可空注册 */
    void registerEnterObjects(CharacterRef cr, int oid, PlayerShop shop) {
        objectWLock.lock();
        try {
            this.mapobjects.put(oid, cr);
        } finally {
            objectWLock.unlock();
        }

        if (shop != null) {
            addMapObject(shop);
        }
    }

    /** 开赛事件图入口关门（原 finishEnter 段；动态 eventstarted 归 map 自读，静态判定走 st） */
    void closeEventJoinPortal() {
        if (isStartingEventMap() && !eventStarted()) {
            getPortal("join00").setPortalStatus(false);
        }
    }

    /** 地图特效初始化数据（原 finishEnter 段；mapEffect 为运行时态，归 map 自读） */
    void sendMapEffectData(Client c) {
        if (mapEffect != null) {
            mapEffect.sendStartData(c);
        }
    }

    public Portal getRandomPlayerSpawnpoint() {
        List<Portal> spawnPoints = new ArrayList<>();
        for (Portal portal : portals.values()) {
            if (portal.getType() >= 0 && portal.getType() <= 1 && portal.getTargetMapId() == MapId.NONE) {
                spawnPoints.add(portal);
            }
        }
        Portal portal = spawnPoints.get(new Random().nextInt(spawnPoints.size()));
        return portal != null ? portal : getPortal(0);
    }

    public Portal findClosestTeleportPortal(Point from) {
        Portal closest = null;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (Portal portal : portals.values()) {
            double distance = portal.getPosition().distanceSq(from);
            if (portal.getType() == Portal.TELEPORT_PORTAL && distance < shortestDistance && portal.getTargetMapId() != MapId.NONE) {
                closest = portal;
                shortestDistance = distance;
            }
        }
        return closest;
    }

    public Portal findClosestPlayerSpawnpoint(Point from) {
        Portal closest = null;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (Portal portal : portals.values()) {
            double distance = portal.getPosition().distanceSq(from);
            if (portal.getType() >= 0 && portal.getType() <= 1 && distance < shortestDistance && portal.getTargetMapId() == MapId.NONE) {
                closest = portal;
                shortestDistance = distance;
            }
        }
        return closest;
    }

    public Portal findClosestPortal(Point from) {
        Portal closest = null;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (Portal portal : portals.values()) {
            double distance = portal.getPosition().distanceSq(from);
            if (distance < shortestDistance) {
                closest = portal;
                shortestDistance = distance;
            }
        }
        return closest;
    }

    public Portal findMarketPortal() {
        for (Portal portal : portals.values()) {
            String ptScript = portal.getScriptName();
            if (ptScript != null && ptScript.contains("market")) {
                return portal;
            }
        }
        return null;
    }

    /*
    public Collection<Portal> getPortals() {
        return Collections.unmodifiableCollection(portals.values());
    }
    */

    public void addPlayerPuppet(CharacterRef cr) {
        for (Monster mm : this.getAllMonsters()) {
            mm.aggroAddPuppet(cr);
        }
    }

    public void removePlayerPuppet(CharacterRef cr) {
        for (Monster mm : this.getAllMonsters()) {
            mm.aggroRemovePuppet(cr);
        }
    }

    /**
     * 离图事实载荷（strict 批次）：player 域收尾（controller 重分配/unregisterChairBuff/
     * leaveMap/PUPPET 效果取消）由 caller 在 player strand 切片完成（Character.leaveMap/
     * removeFacts），本载荷只携带 map 域摘除所需的键与快照。
     *
     * @param cid     离图角色 id（= 地图 oid）
     * @param hidden  GM 隐身快照（决定 removePlayerFromMap 走普通/GM 广播）
     * @param party   party 快照（caller strand 采集，registerPlayer 同款）
     * @param summons summon 快照（非固定者从图上摘除；固定者 PUPPET 效果取消已在 caller）
     */
    public record RemoveFacts(int cid, boolean hidden, Party party, List<Summon> summons) {
    }

    /**
     * 离图摘除 + controller 登记摘除/换届（map actor 单任务体，载荷键控零 CharacterRef
     * 触达——strict 批次产物）。换届排在 characters 摘除之后（候选集不含离场者）。
     * player 域收尾归 caller 切片（CharacterMap.changeMapInternal / Client 离图路径：
     * leaveMiniDungeon/leaveMap 前置、setMapId 过滤基准切换后单笔 postLeaveMap 投递）。
     */
    public void removePlayer(RemoveFacts facts) {
        chrWLock.lock();
        try {
            if (facts.party() != null && facts.party().getMemberById(facts.cid()) != null) {
                removePartyMemberInternal(facts.cid(), facts.party().getId());
            }

            characters.removeIf(c -> c.getId() == facts.cid());
        } finally {
            chrWLock.unlock();
        }

        // controller 登记簿摘除（原"release 前置 + 此处兜底"两段合一）：换届循环在本任务体
        // 尾段执行——此时 characters 已摘除离场者，重选举候选集不含他，幽灵 grant（旧图怪被
        // 重新授控给已切图者）结构性排除。对离场者的 stop 闭包沿 mapId 过滤在其 strand 抛弃
        // （无回显）；留守新任 controller 正常收 grant。
        List<Integer> releasedOids = releaseControlledMonsters(facts.cid());
        removeMapObject(facts.cid());
        if (!facts.hidden()) {
            broadcastMessage(PacketCreator.removePlayerFromMap(facts.cid()));
        } else {
            broadcastGMMessage(PacketCreator.removePlayerFromMap(facts.cid()));
        }

        for (Summon summon : facts.summons()) {
            if (!summon.isStationary()) {
                removeMapObject(summon);
            }
        }

        // controller 换届（原 MapleMapRef.releaseControlledMonsters 的 post 段并入本任务体）：
        // 图空则换届为 null（无包）。
        for (int oid : releasedOids) {
            Monster monster = getMonsterByOid(oid);
            if (monster != null) {
                monster.aggroRedirectController();
            }
        }
    }

    /**
     * 被动清理本图上"已断线（isAwayFromWorld）却未被正常移除"的幽灵玩家。
     * 在 addPlayer 时触发：新玩家进图前先清掉幽灵并广播 removePlayerFromMap，
     * 使新玩家与图上原有玩家都不再看到这个已下线的角色。配合 Client.removePlayer 的 A 修复兜底漏网情况。
     * awayFromWorld=true 涵盖已断开/商城/mts，这类玩家本就不该留在地图 characters，留在即幽灵，正常在线玩家 awayFromWorld=false 不受影响。
     */
    private void cleanupGhostPlayers() {
        List<CharacterRef> ghosts = new ArrayList<>();
        chrRLock.lock();
        try {
            for (CharacterRef cr : characters) {
                CharacterRef c = cr;
                if (c != null && c.isAwayFromWorld()) {
                    ghosts.add(c);
                }
            }
        } finally {
            chrRLock.unlock();
        }

        for (CharacterRef ghost : ghosts) {
            log.warn("检测到幽灵玩家（已断线未正常移除），被动清理. mapId={} ghostChr={}", st.mapid(), ghost.getName());
            try {
                // 幽灵会话已终结（strictMode 必为 false），ref 读不触发 canary；PUPPET 效果
                // 取消随会话消亡失去意义，故不搬 player 切片、就地组载荷。
                // isHidden 按"恒 false"裁定（本版本无 GM）——hidden=false 字面量。
                removePlayer(new RemoveFacts(ghost.getId(), false, ghost.getParty(),
                        new ArrayList<>(ghost.getSummonsValues())));
            } catch (Throwable t) {
                // 单个幽灵清理失败不应影响其他幽灵清理，也不应阻断 addPlayer 流程
                log.error("清理幽灵玩家异常 mapId={} ghostChr={}", st.mapid(), ghost.getName(), t);
            }
        }
    }

    /**
     * 无条件地将消息广播给所有玩家。
     *
     * Broadcasts a message to all players without any conditions.
     *
     * @param {Packet} packet - 要广播的数据包。The packet to be broadcasted.
     */
    public void broadcastMessage(Packet packet) {
        broadcastMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    /**
     * 无条件地将管理员消息广播给所有玩家。
     *
     * Broadcasts an admin message to all players without any conditions.
     *
     * @param {Packet} packet - 要广播的数据包。The packet to be broadcasted.
     */
    public void broadcastGMMessage(Packet packet) {
        broadcastGMMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    /**
     * 根据 repeatToSource 参数决定是否将消息重复发送给源角色，并无范围限制地广播消息。
     *
     * Broadcasts a message based on the repeatToSource parameter, repeating it to the source character if specified,
     * and broadcasts it without any range restrictions.
     *
     * @param {CharacterRef} source - 消息的源角色。The source character of the message.
     * @param {Packet} packet - 要广播的数据包。The packet to be broadcasted.
     * @param {boolean} repeatToSource - 是否重复发送给源角色。Whether to repeat the message to the source character.
     */
    public void broadcastMessage(Character source, Packet packet) {
        broadcastMessage(CharacterRef.of(source), packet);
    }

    public void broadcastMessage(CharacterRef source, Packet packet) {
        broadcastMessage(source, packet, false);
    }

    /**
     * 根据 repeatToSource 和 ranged 参数决定是否将消息重复发送给源角色以及是否限定在一定范围内广播消息。
     *
     * Broadcasts a message based on the repeatToSource and ranged parameters, repeating it to the source character if specified,
     * and broadcasting it within a certain range if ranged is true.
     *
     * @param {CharacterRef} source - 消息的源角色。The source character of the message.
     * @param {Packet} packet - 要广播的数据包。The packet to be broadcasted.
     * @param {boolean} repeatToSource - 是否重复发送给源角色。Whether to repeat the message to the source character.
     * @param {boolean} ranged - 是否限定在一定范围内广播消息。Whether to broadcast the message within a certain range.
     */
    public void broadcastMessage(CharacterRef source, Packet packet, boolean repeatToSource) {
        broadcastMessage(source, packet, repeatToSource, false);
    }

    public void broadcastMessage(CharacterRef source, Packet packet, boolean repeatToSource, boolean ranged) {
        // 锚点惰性读取：非 ranged（INF）广播不使用锚点——原实现对 source.getPosition() 的
        // 无条件读会在 source 的 strict 窗口开着时触发 canary（如 enterMap 内的宠物重生广播）。
        broadcastMessage(repeatToSource ? null : source, packet,
                ranged ? getRangedDistance() : Double.POSITIVE_INFINITY,
                ranged ? source.getPosition() : null);
    }

    /**
     * 从指定点开始，在一定范围内广播消息。
     *
     * Broadcasts a message starting from a specified point within a certain range.
     *
     * @param {Packet} packet - 要广播的数据包。The packet to be broadcasted.
     * @param {Point} rangedFrom - 广播的起点位置。The starting point for broadcasting.
     */
    /**
     * 近战攻击 phase 2 入口（player→map 通知，异步）：载荷 = 不可变 Battle.CloseRangeAttackIntent。
     * 由 {@link MapleMapRef#applyCloseRangeAttack} post 进 shim，map actor 串行执行。
     */
    public void applyCloseRangeAttack(Battle.CloseRangeAttackIntent intent) {
        battle.applyCloseRangeAttack(intent);
    }

    /**
     * 攻击中继广播（map actor → 各接收方，postLegacyPacket 过渡桥）：受众语义与
     * broadcastMessage(source, packet, false, true) 一致（排除 source + ranged 视野 +
     * 断连清理），仅发送改 postLegacyPacket——接收方 strand 窗口收口后经 ref 触 client
     * （map actor 直发 client 的过渡替代；攻击中继 S→C 语义化后删除）。
     */
    /**
     * 怪物 HP 变化广播（map actor → 各接收方）：受众 = 怪物交战集 ∩ 本图在线玩家。
     * 本图玩家表是异步快照，只做投递解析——消息携带 mapId，接收方 actor 校验
     * "我在的图 == 怪物所在图"（唯一知道玩家当前时点位置的是 Player actor 自己）。
     */
    public void broadcastMonsterHp(Monster monster, int hpPercent) {
        chrRLock.lock();
        try {
            // 队伍归属经视图活取（player actor 任务边界发布，最终一致）——
            // 后入队者下一次攻击即入受众，无快照时点问题
            Set<Integer> engagedParties = new HashSet<>();
            for (CharacterRef cr : characters) {
                if (!cr.isClientDisconnected() && monster.isEngaged(cr.getId())) {
                    int pid = cr.getPartyId();
                    if (pid > 0) {
                        engagedParties.add(pid);
                    }
                }
            }
            for (CharacterRef cr : characters) {
                if (cr.isClientDisconnected()) {
                    continue;
                }
                int pid = cr.getPartyId();
                if (monster.isEngaged(cr.getId()) || (pid > 0 && engagedParties.contains(pid))) {
                    cr.post(new MapMonsterHpMessage(getId(), monster.getObjectId(), hpPercent));
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public void broadcastAttackRelay(CharacterRef source, Packet packet) {
        final double rangeSq = getRangedDistance();
        final Point rangedFrom = rangeSq < Double.POSITIVE_INFINITY ? source.getPosition() : null;
        chrRLock.lock();
        try {
            Iterator<CharacterRef> iterator = characters.iterator();
            while (iterator.hasNext()) {
                CharacterRef chr = iterator.next();
                if (chrDisconnected(iterator, chr)) {
                    continue;
                }
                if (chr != source && (rangedFrom == null || rangedFrom.distanceSq(chr.getPosition()) <= rangeSq)) {
                    chr.postLegacyPacket(getId(), "close-range-relay", client -> client.sendPacket(packet));
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public void broadcastMessage(Packet packet, Point rangedFrom) {
        broadcastMessage(null, packet, getRangedDistance(), rangedFrom);
    }

    /**
     * 从指定点开始，在一定范围内广播消息，并且不向源角色发送消息。
     *
     * Broadcasts a message starting from a specified point within a certain range and does not send it to the source character.
     *
     * @param {CharacterRef} source - 消息的源角色。The source character of the message.
     * @param {Packet} packet - 要广播的数据包。The packet to be broadcasted.
     * @param {Point} rangedFrom - 广播的起点位置。The starting point for broadcasting.
     */
    public void broadcastMessage(CharacterRef source, Packet packet, Point rangedFrom) {
        broadcastMessage(source, packet, getRangedDistance(), rangedFrom);
    }

    /**
     * 核心广播方法，负责实际的消息分发工作。
     *
     * Core method responsible for actually dispatching the message.
     *
     * @param {CharacterRef} source - 消息的源角色。The source character of the message.
     * @param {Packet} packet - 要广播的数据包。The packet to be broadcasted.
     * @param {double} rangeSq - 广播的最大距离平方值。The maximum distance squared for broadcasting.
     * @param {Point} rangedFrom - 广播的起点位置。The starting point for broadcasting.
     */
    private void broadcastMessage(CharacterRef source, Packet packet, double rangeSq, Point rangedFrom) {
        chrRLock.lock();
        try {
            Iterator<CharacterRef> iterator = characters.iterator();
            while (iterator.hasNext()) {
                CharacterRef chr = iterator.next();
                if (chrDisconnected(iterator, chr)) {
                    continue;
                }
                if (source == null || chr != source) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.sendPacket(packet);
                        }
                    } else {
                        chr.sendPacket(packet);
                    }
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    private boolean chrDisconnected(Iterator<CharacterRef> iterator, CharacterRef chr) {
        // 如果玩家已经掉线，则移除地图该玩家，但不确保频道、大区该玩家是否仍会引发异常。
        // 断连为 ref 快照读（方向 2 读快照化）——原 getClient() 活体读会在目标角色 strict
        // 窗口开着时触发 canary（广播循环对含 source 在内的全体角色做该检查）。
        if (chr == null || chr.isClientDisconnected()) {
            iterator.remove();
            return true;
        }
        return false;
    }

    private void updateBossSpawn(Monster monster) {
        if (monster.hasBossHPBar()) {
            broadcastBossHpMessage(monster, monster.hashCode(), monster.makeBossHPBarPacket(), monster.getPosition());
        }
    }

    public void broadcastBossHpMessage(Monster mm, int bossHash, Packet packet) {
        broadcastBossHpMessage(mm, bossHash, null, packet, Double.POSITIVE_INFINITY, null);
    }

    public void broadcastBossHpMessage(Monster mm, int bossHash, Packet packet, Point rangedFrom) {
        broadcastBossHpMessage(mm, bossHash, null, packet, getRangedDistance(), rangedFrom);
    }

    private void broadcastBossHpMessage(Monster mm, int bossHash, CharacterRef source, Packet packet, double rangeSq, Point rangedFrom) {
        chrRLock.lock();
        try {
            for (CharacterRef cr : characters) {
                CharacterRef chr = cr;
                if (chr != source) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.getClient().announceBossHpBar(mm, bossHash, packet);
                        }
                    } else {
                        chr.getClient().announceBossHpBar(mm, bossHash, packet);
                    }
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    private void broadcastItemDropMessage(MapItem mdrop, Point dropperPos, Point dropPos, byte mod, Point rangedFrom) {
        broadcastItemDropMessage(mdrop, dropperPos, dropPos, mod, getRangedDistance(), rangedFrom);
    }

    private void broadcastItemDropMessage(MapItem mdrop, Point dropperPos, Point dropPos, byte mod) {
        broadcastItemDropMessage(mdrop, dropperPos, dropPos, mod, Double.POSITIVE_INFINITY, null);
    }

    private void broadcastItemDropMessage(MapItem mdrop, Point dropperPos, Point dropPos, byte mod, double rangeSq, Point rangedFrom) {
        chrRLock.lock();
        try {
            Iterator<CharacterRef> iterator = characters.iterator();
            while (iterator.hasNext()) {
                CharacterRef chr = iterator.next();
                if (chrDisconnected(iterator, chr)) {
                    continue;
                }
                Packet packet = PacketCreator.dropItemFromMapObject(chr.unref(), mdrop, dropperPos, dropPos, mod);

                if (rangeSq < Double.POSITIVE_INFINITY) {
                    if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                        chr.sendPacket(packet);
                    }
                } else {
                    chr.sendPacket(packet);
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public void broadcastSpawnPlayerMapObjectMessage(CharacterRef source, CharacterRef player, boolean enteringField) {
        broadcastSpawnPlayerMapObjectMessage(source, player, enteringField, false);
    }

    private void broadcastSpawnPlayerMapObjectMessage(CharacterRef source, CharacterRef player, boolean enteringField, boolean gmBroadcast) {
        chrRLock.lock();
        try {
            if (gmBroadcast) {
                Iterator<CharacterRef> iterator = characters.iterator();
                while (iterator.hasNext()) {
                    CharacterRef chr = iterator.next();
                    if (chrDisconnected(iterator, chr)) {
                        continue;
                    }
                    if (chr.isGM()) {
                        if (chr != source) {
                            chr.sendPacket(PacketCreator.spawnPlayerMapObject(chr.getClient(), player.unref(), enteringField));
                        }
                    }
                }
            } else {
                Iterator<CharacterRef> iterator = characters.iterator();
                while (iterator.hasNext()) {
                    CharacterRef chr = iterator.next();
                    if (chrDisconnected(iterator, chr)) {
                        continue;
                    }
                    if (chr != source) {
                        chr.sendPacket(PacketCreator.spawnPlayerMapObject(chr.getClient(), player.unref(), enteringField));
                    }
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public void broadcastUpdateCharLookMessage(CharacterRef source, CharacterRef player) {
        chrRLock.lock();
        try {
            Iterator<CharacterRef> iterator = characters.iterator();
            while (iterator.hasNext()) {
                CharacterRef chr = iterator.next();
                if (chrDisconnected(iterator, chr)) {
                    continue;
                }
                if (source == null || chr != source) {
                    chr.sendPacket(player.updateCharLookPacket(chr.getClient()));
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public void dropMessage(int type, String message) {
        broadcastStringMessage(type, message);
    }

    public void broadcastStringMessage(int type, String message) {
        broadcastMessage(PacketCreator.serverNotice(type, message));
    }

    private static boolean isNonRangedType(MapObjectType type) {
        switch (type) {
            case NPC:
            case PLAYER:
            case HIRED_MERCHANT:
            case PLAYER_NPC:
            case DRAGON:
            case MIST:
            case KITE:
                return true;
            default:
                return false;
        }
    }

    public List<MapObject> getMapObjectsInRange(Point from, double rangeSq, List<MapObjectType> types) {
        List<MapObject> ret = new LinkedList<>();
        objectRLock.lock();
        try {
            for (MapObject l : mapobjects.values()) {
                if (types.contains(l.getType())) {
                    if (from.distanceSq(l.getPosition()) <= rangeSq) {
                        ret.add(l);
                    }
                }
            }
            return ret;
        } finally {
            objectRLock.unlock();
        }
    }

    public List<MapObject> getMapObjectsInBox(Rectangle box, List<MapObjectType> types) {
        List<MapObject> ret = new LinkedList<>();
        objectRLock.lock();
        try {
            for (MapObject l : mapobjects.values()) {
                if (types.contains(l.getType())) {
                    if (box.contains(l.getPosition())) {
                        ret.add(l);
                    }
                }
            }
            return ret;
        } finally {
            objectRLock.unlock();
        }
    }

    public void addPortal(Portal myPortal) {
        portals.put(myPortal.getId(), myPortal);
    }

    public Portal getPortal(String portalname) {
        for (Portal port : portals.values()) {
            if (port.getName().equals(portalname)) {
                return port;
            }
        }
        return null;
    }

    public Portal getPortal(int portalid) {
        return portals.get(portalid);
    }

    // ── portal 门禁（动态半归 map actor；player strand 经 ref 快照/意图方法访问）──

    /** 脚本门门禁快照（动态三元组入域时点抽取；查无门返回 null） */
    public PortalGateSnap portalGate(String portalName) {
        Portal p = getPortal(portalName);
        return p == null ? null : new PortalGateSnap(p.getScriptName(), p.getPortalStatus(), p.getPortalState());
    }

    public void setPortalStatus(String portalName, boolean open) {
        Portal p = getPortal(portalName);
        if (p != null) {
            p.setPortalStatus(open);
        }
    }

    public void setPortalState(String portalName, boolean state) {
        Portal p = getPortal(portalName);
        if (p != null) {
            p.setPortalState(state);
        }
    }

    public void setPortalScript(String portalName, String script) {
        Portal p = getPortal(portalName);
        if (p != null) {
            p.setScriptName(script);
        }
    }

    public FootholdTree getFootholds() {
        return st.footholds();
    }

    public MonsterAggroCoordinator getAggroCoordinator() {
        return aggroMonitor;
    }

    /**
     * it's threadsafe, gtfo :D
     *
     * @param monster
     * @param mobTime
     */
    public void addMonsterSpawn(Monster monster, int mobTime, int team) {
        Point newpos = calcPointBelow(monster.getPosition());
        newpos.y -= 1;
        SpawnPoint sp = new SpawnPoint(monster, newpos, !monster.isMobile(), mobTime, st.mobInterval(), team);
        monsterSpawn.add(sp);
        if (sp.shouldSpawn() || mobTime == -1) {// -1 does not respawn and should not either but force ONE spawn
            spawnMonster(sp.getMonster());
        }
    }

    public void addAllMonsterSpawn(Monster monster, int mobTime, int team) {
        Point newpos = calcPointBelow(monster.getPosition());
        newpos.y -= 1;
        SpawnPoint sp = new SpawnPoint(monster, newpos, !monster.isMobile(), mobTime, st.mobInterval(), team);
        allMonsterSpawn.add(sp);
    }

    public void removeMonsterSpawn(int mobId, int x, int y) {
        // assumption: spawn points identifies by tuple (lifeid, x, y)

        Point checkpos = calcPointBelow(new Point(x, y));
        checkpos.y -= 1;

        List<SpawnPoint> toRemove = new LinkedList<>();
        for (SpawnPoint sp : getMonsterSpawn()) {
            Point pos = sp.getPosition();
            if (sp.getMonsterId() == mobId && checkpos.equals(pos)) {
                toRemove.add(sp);
            }
        }

        if (!toRemove.isEmpty()) {
            synchronized (monsterSpawn) {
                for (SpawnPoint sp : toRemove) {
                    monsterSpawn.remove(sp);
                }
            }
        }
    }

    public void removeAllMonsterSpawn(int mobId, int x, int y) {
        // assumption: spawn points identifies by tuple (lifeid, x, y)

        Point checkpos = calcPointBelow(new Point(x, y));
        checkpos.y -= 1;

        List<SpawnPoint> toRemove = new LinkedList<>();
        for (SpawnPoint sp : getAllMonsterSpawn()) {
            Point pos = sp.getPosition();
            if (sp.getMonsterId() == mobId && checkpos.equals(pos)) {
                toRemove.add(sp);
            }
        }

        if (!toRemove.isEmpty()) {
            synchronized (allMonsterSpawn) {
                for (SpawnPoint sp : toRemove) {
                    allMonsterSpawn.remove(sp);
                }
            }
        }
    }

    public void reportMonsterSpawnPoints(CharacterRef chr) {
        // 输出地图刷怪点统计信息头
        chr.dropMessage(6, "┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        chr.dropMessage(6, "┃ 地图ID: " + getId() + " | 总刷怪点: " + monsterSpawn.size() +  " | 已刷怪: " + spawnedMonstersOnMap.get());
        chr.dropMessage(6, "┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // 遍历所有刷怪点输出详细信息
        for (SpawnPoint sp : getAllMonsterSpawn()) {
            chr.dropMessage(6,
                    "┃ ID:" + sp.getMonsterId() + " | 可刷怪:" + (sp.getDenySpawn() ? "×" : "√") + " | 现存:" + sp.getSpawned() + "\n" +
                    "┃ 坐标:(" +(int) sp.getPosition().getX() + " , " + (int) sp.getPosition().getY() + ") | 刷新:" + sp.getMobTime() + "ms | 阵营:" + sp.getTeam()
            );
        }
        chr.dropMessage(6, "┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    public Map<Integer, CharacterRef> getMapPlayers() {
        chrRLock.lock();
        try {
            Map<Integer, CharacterRef> mapChars = new HashMap<>(characters.size());

            for (CharacterRef cr : characters) {
                CharacterRef chr = cr;
                mapChars.put(chr.getId(), chr);
            }

            return mapChars;
        } finally {
            chrRLock.unlock();
        }
    }

    public Collection<CharacterRef> getCharacters() {
        chrRLock.lock();
        try {
            return Collections.unmodifiableCollection(this.characters);
        } finally {
            chrRLock.unlock();
        }
    }

    /**
     * 在图判定（权威信源）：characters 集合按 cid 成员判定——"该角色当前在本图"的时点事实。
     *
     * <p>TODO(保活)：摘除依赖离场/断线路径及时到达（cleanupGhostPlayers 的存在即旁证滞留
     * 场景存在）；若幽灵滞留，此处会误判其仍在图——保活机制补齐前接受该近似。
     */
    public boolean hasCharacter(int cid) {
        chrRLock.lock();
        try {
            for (CharacterRef cr : characters) {
                if (cr.getId() == cid) {
                    return true;
                }
            }
            return false;
        } finally {
            chrRLock.unlock();
        }
    }

    // ── controller 登记簿（cid → 受控怪 oid 集；map actor 域内读写，随 removePlayer/release 清理）──
    // 原 Character.controlled（player 域持活 Monster 引用集合 + map 线程跨域直写）的 map 域重构：
    // mob 移动权威本就是 map 域状态（Monster.controller），per-cid 索引只为选举计数与离场换届。

    private final Map<Integer, Set<Integer>> controlledMonsters = new HashMap<>();

    public void registerControlledMonster(int cid, int monsterOid) {
        controlledMonsters.computeIfAbsent(cid, k -> new HashSet<>()).add(monsterOid);
    }

    public void unregisterControlledMonster(int cid, int monsterOid) {
        Set<Integer> oids = controlledMonsters.get(cid);
        if (oids != null) {
            oids.remove(monsterOid);
            if (oids.isEmpty()) {
                controlledMonsters.remove(cid);
            }
        }
    }

    public int getControlledMonsterCount(int cid) {
        Set<Integer> oids = controlledMonsters.get(cid);
        return oids != null ? oids.size() : 0;
    }

    /** 受控怪 oid 集（只读投影；map actor 域内，消费方逐 oid 解析活对象） */
    public Set<Integer> getControlledMonsterOids(int cid) {
        Set<Integer> oids = controlledMonsters.get(cid);
        return oids != null ? oids : Set.of();
    }

    /** 离场换届：摘除该玩家全部受控登记，返回 oid 集（调用方逐只重选举） */
    public List<Integer> releaseControlledMonsters(int cid) {
        Set<Integer> oids = controlledMonsters.remove(cid);
        return oids != null ? new ArrayList<>(oids) : List.of();
    }

    public CharacterRef getCharacterById(int id) {
        chrRLock.lock();
        try {
            for (CharacterRef cr : this.characters) {
                CharacterRef chr = cr;
                if (chr.getId() == id) {
                    return chr;
                }
            }
        } finally {
            chrRLock.unlock();
        }
        return null;
    }

    private void updateMapObjectVisibility(CharacterRef chr, MapObject mo) {
        if (!chr.isMapObjectVisible(mo.getObjectId())) { // object entered view range
            if (mo.getType() == MapObjectType.SUMMON || mo.getPosition().distanceSq(chr.getPosition()) <= getRangedDistance()) {
                List<MapView.MonsterView> mv = mo.getType() == MapObjectType.MONSTER
                        ? List.of(MapView.MonsterView.of((Monster) mo)) : List.of();
                chr.post(new MapObjectsViewMessage(getId(), List.of(viewEntry(mo)), List.of(), mv));
                mo.sendSpawnData(chr.getClient());
            }
        } else if (mo.getType() != MapObjectType.SUMMON && mo.getPosition().distanceSq(chr.getPosition()) > getRangedDistance()) {
            chr.post(new MapObjectsViewMessage(getId(), List.of(), List.of(mo.getObjectId())));
            mo.sendDestroyData(chr.getClient());
        }
    }

    public void moveMonster(Monster monster, Point reportedPos) {
        monster.setPosition(reportedPos);
        for (CharacterRef chr : getAllPlayers()) {
            updateMapObjectVisibility(chr, monster);
        }
    }

    /**
     * 移动消息（player actor → map actor，doc/13 §12）：携带差集计算与广播所需的全部事实，
     * map 任务体零 player 状态读。
     */

    // relayPacket == null：同图内传送等无中继广播的位移（仅可见性差集）

    /**
     * move 事件（map actor 任务体，串行执行）：可见性差集（编码直发）+ 他人流广播 +
     * 回程 post（可见集应用归 player actor，幂等）。
     * 快照过期（apply 回程与新 move 竞态 <1ms 窗口）→ 重复 spawn 包：oid 寻址自愈，已知中间态。
     */
    /**
     * 角色移动他人流中继（map actor，doc/13 §12 的广播时点形态）：受众=本图非断连角色，
     * source 按 ref 自持 id 排除；投递为接收方连接视角的语义通知（remote.map().characterMove，
     * 编码+发送归各端 remote）。
     */
    public void broadcastCharacterMove(int charId, List<MoveElement> movements) {
        chrRLock.lock();
        try {
            for (CharacterRef cr : characters) {
                if (cr.isClientDisconnected() || cr.getId() == charId) {
                    continue;
                }
                cr.post(new MapCharacterMoveMessage(getId(), charId, movements));
            }
        } finally {
            chrRLock.unlock();
        }
    }

    /**
     * 角色完成任务他人流中继（map actor，同 broadcastCharacterMove 形态）：受众=本图非断连
     * 角色，source 按自持 id 排除；投递为接收方连接视角的语义通知（remote.map().characterQuestComplete）。
     */
    public void broadcastQuestComplete(int charId) {
        chrRLock.lock();
        try {
            for (CharacterRef cr : characters) {
                if (cr.isClientDisconnected() || cr.getId() == charId) {
                    continue;
                }
                cr.post(new MapQuestCompleteMessage(getId(), charId));
            }
        } finally {
            chrRLock.unlock();
        }
    }

    /**
     * 角色移动的可见性差集应用（map actor，doc/13 §12；原 onMove/MoveMsg 的参数直提形态——
     * strand 归 {@link CharacterRef} 自持）：消失集 destroy 包 + 新现集 spawn 包经
     * postLegacyPacket 回移动者 strand 发送（本任务体零 client 直触，窗口按调度豁免
     * 约定随之移除），包序 = destroy 流在前、spawn 流在后（与原两段循环一致），
     * 差集以值消息（{@link MapObjectsViewMessage}）回投 player strand 登记可见视图。
     * 入参为移动者可见视图的 oid 快照（原活引用列表的值化）；oid 单调分配回绕前不复用，
     * 原身份比对（mapObjects.get(oid) != mo）由「查无此 oid」等价替代。
     */
    public void handleCharacterMove(CharacterRef chr, Point toPos, List<Integer> visibleOids) {
        List<MapObject> addRefs = new ArrayList<>();
        List<Integer> removeOids = new ArrayList<>();
        List<MapObject> destroySends = new ArrayList<>();
        Map<Integer, MapObject> mapObjects = getCopyMapObjects();
        Set<Integer> visibleSet = new HashSet<>(visibleOids);
        for (Integer oidBox : visibleOids) {
            int oid = oidBox;
            MapObject mo = mapObjects.get(oid);
            if (mo == null) {
                // 对象已不在图上：现状语义为静默移除（无包）
                removeOids.add(oid);
            } else if (mo.getType() != MapObjectType.SUMMON
                    && mo.getPosition().distanceSq(toPos) > getRangedDistance()) {
                destroySends.add(mo);
                removeOids.add(oid);
            }
        }
        List<MapView.Entry> addEntries = new ArrayList<>();
        List<MapView.MonsterView> monsterViews = new ArrayList<>();
        for (MapObject mo : getMapObjectsInRange(toPos, getRangedDistance(), rangedMapobjectTypes)) {
            if (!visibleSet.contains(mo.getObjectId())) {
                addRefs.add(mo);
                addEntries.add(viewEntry(mo));
                if (mo.getType() == MapObjectType.MONSTER) {
                    monsterViews.add(MapView.MonsterView.of((Monster) mo));
                }
            }
        }

        if (!destroySends.isEmpty() || !addRefs.isEmpty()) {
            chr.postLegacyPacket(getId(), "handleCharacterMove", client -> {
                for (MapObject mo : destroySends) {
                    mo.sendDestroyData(client);
                }
                for (MapObject mo : addRefs) {
                    mo.sendSpawnData(client);
                }
            });
        }

        chr.post(new MapObjectsViewMessage(getId(), addEntries, removeOids, monsterViews));
    }

    /**
     * 切图完成确认的 mob 视图重建（map actor 任务体，doc/13 §18；PLAYER_MAP_TRANSFER
     * 的 map 侧半段）：对视野内 mob 做 revoke 控制 → destroy → respawn → 重挂 controller，
     * 修复客户端切图后的 mob 状态显示。chr 的 player 侧状态已在 strand 读完（isHidden
     * 快照门在调用方）；mob 侧状态照旧并发语义。
     *
     * <p>载荷为 {@link CharacterRef}：controller 的 identity 比较按 id；重挂与收回的
     * 发包在 Monster 侧经 postLegacyPacket 回 strand，本任务体零本体触达。
     */
    public void onTransitionMobView(CharacterRef chr) {
        List<Consumer<Client>> sends = new ArrayList<>();
        List<Monster> regrant = new ArrayList<>();
        List<Boolean> regrantMine = new ArrayList<>();
        for (MapObject mo : getMonsters()) {    // thanks BHB, IxianMace, Jefe for noticing several issues regarding mob statuses (such as freeze)
            Monster m = (Monster) mo;
            if (m.getSpawnEffect() == 0 || m.getHp() < m.getMaxHp()) {     // avoid effect-spawning mobs
                CharacterRef controller = m.getController();
                if (controller != null && controller.getId() == chr.getId()) {   // identity = id（跨实例稳健；引用 == 会误判重连后的新旧实例）
                    sends.add(client -> client.sendPacket(PacketCreator.stopControllingMonster(m.getObjectId())));
                    sends.add(m::sendDestroyData);
                    regrant.add(m);
                    regrantMine.add(true);
                } else {
                    sends.add(m::sendDestroyData);
                    regrant.add(m);
                    regrantMine.add(false);
                }
                sends.add(m::sendSpawnData);
            }
        }
        // 重建包按收集序整体回移动者 strand 直发（窗口收口后执行）。
        chr.postLegacyPacket(getId(), "map-transitionMobView", client -> {
            for (Consumer<Client> send : sends) {
                send.accept(client);
            }
        });
        // controller 摘除/重挂延后于重建批次入队执行：plain SPAWN_MONSTER 会重置 client 控制
        // 位，grant 必须落在批次内 spawn 之后才生效（旧内联实现 grant 落尾；批次化后 grant 于
        // 循环内先入队 = 登录/切图后怪全部不受控回归根因）。map 域状态变更仍在本任务体内完成。
        for (int i = 0; i < regrant.size(); i++) {
            Monster m = regrant.get(i);
            if (regrantMine.get(i)) {
                m.aggroRemoveController();
            }
            m.aggroSwitchController(chr, false);
        }
    }

    /**
     * mob 控制移动消息（player actor → map actor，doc/13 §18）：MOVE_LIFE 的全部语义
     * 事实（gms083 纯解码产出）。player 事实只剩不可变身份（chr 引用仅作 id 比较/广播
     * source），map 任务体零 player 可变状态读。
     */
    public record MoveLifeMsg(CharacterRef chr, Client client, RemoteClient remote, MoveLife life) {
    }

    /**
     * move-life 事件（map actor 任务体，串行执行）：MoveLifeHandler 主体的 verbatim
     * 迁移——活动判定/mob 技能与攻击门控/controller 校验/位置应用/ack/中继广播/可见性。
     * FIXME: banish（BAN 技能的玩家传送，原 banishPlayers → changeMapBanish）暂不实现——
     * 官方重启版数据一年内不会出现 banish；补齐时不得从 map 任务体阻塞等 player strand
     * （应 post 目标 strand）。
     */
    /**
     * 怪物移动他人流中继（map actor）：受众 = controller 以外、anchor 视野范围内的
     * 非断连角色，逐接收方消息投递（编码+发送回接收方 strand 执行——包构建归 remote）。
     */
    public void broadcastMonsterMove(MonsterMove move, Point rangeAnchor, CharacterRef source) {
        chrRLock.lock();
        try {
            for (CharacterRef cr : characters) {
                if (cr.isClientDisconnected() || cr == source) {
                    continue;
                }
                if (cr.getPosition().distanceSq(rangeAnchor) <= getRangedDistance()) {
                    cr.post(new MapMonsterMoveMessage(getId(), move));
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public void onMoveLife(MoveLifeMsg msg) {
        MoveLife life = msg.life();
        MapObject mmo = getMapObject(life.oid());
        if (mmo == null || mmo.getType() != MapObjectType.MONSTER) {
            return;
        }
        Monster monster = (Monster) mmo;
        CharacterRef player = msg.chr();

        byte pNibbles = life.pNibbles();
        byte rawActivity = life.rawActivity();
        int skillId = life.skillId();
        int skillLv = life.skillLv();
        short pOption = life.pOption();

        if (rawActivity >= 0) {
            rawActivity = (byte) (rawActivity & 0xFF >> 1);
        }

        boolean isSkill = inRangeInclusive(rawActivity, 42, 59);

        int useSkillId = 0;
        int useSkillLevel = 0;

        if (isSkill) {
            useSkillId = skillId;
            useSkillLevel = skillLv;

            if (monster.hasSkill(useSkillId, useSkillLevel)) {
                MobSkillType mobSkillType = MobSkillType.from(useSkillId).orElseThrow();
                MobSkill toUse = MobSkillFactory.getMobSkillOrThrow(mobSkillType, useSkillLevel);

                if (monster.canUseSkill(toUse, true)) {
                    int animationTime = MonsterInformationProvider.getInstance().getMobSkillAnimationTime(toUse);
                    if (animationTime > 0 && toUse.getType() != MobSkillType.BANISH) {
                        toUse.applyDelayedEffect(player.unref(), monster, true, animationTime);
                    } else {
                        toUse.applyEffect(player.unref(), monster, true, new LinkedList<>());   // FIXME: banish 玩家传送缺位
                    }
                }
            }
        } else {
            int castPos = (rawActivity - 24) / 2;
            int atkStatus = monster.canUseAttack(castPos, isSkill);
            if (atkStatus < 1) {
                rawActivity = -1;
                pOption = 0;
            }
        }

        boolean nextMovementCouldBeSkill = !(isSkill || (pNibbles != 0));
        int nextSkillId = 0;
        int nextSkillLevel = 0;
        int mobMp = monster.getMp();
        if (nextMovementCouldBeSkill && monster.hasAnySkill()) {
            MobSkillId skillToUse = monster.getRandomSkill();
            nextSkillId = skillToUse.type().getId();
            nextSkillLevel = skillToUse.level();
            MobSkill nextUse = MobSkillFactory.getMobSkillOrThrow(skillToUse.type(), skillToUse.level());

            if (!(nextUse != null && monster.canUseSkill(nextUse, false) && nextUse.getHP() >= (int) (((float) monster.getHp() / monster.getMaxHp()) * 100) && mobMp >= nextUse.getMpCon())) {
                // thanks OishiiKawaiiDesu for noticing mobs trying to cast skills they are not supposed to be able
                nextSkillId = 0;
                nextSkillLevel = 0;
            }
        }

        Boolean aggro = monster.aggroMoveLifeUpdate(player);
        if (aggro == null) {
            return;
        }

        msg.remote().map().ackMoveMonster(life.oid(), life.moveid(), mobMp, aggro, nextSkillId, nextSkillLevel);

        // 位置应用（updatePosition monster 分支语义）→ 他人流中继（逐接收方语义投递）→ 可见性维护
        Point serverStartPos = new Point(monster.getPosition());
        applyLifeMovement(monster, life.elements());
        broadcastMonsterMove(new MonsterMove(life.oid(), nextMovementCouldBeSkill,
                rawActivity, useSkillId, useSkillLevel, pOption, life.startPos(), life.elements()),
                serverStartPos, player);
        moveMonster(monster, monster.getPosition());
    }

    /**
     * life 移动元素的位置应用：自 AbstractMovementPacketHandler.updatePosition 的
     * monster 分支迁移（yOffset = -2；相对移动对 mob 不推算落点仅同步姿态；command 11
     * 在 life 语法下是瞬移形布局、语义仅同步姿态；10/14/21 无位置效果，仅随中继重放字节）。
     */
    private static void applyLifeMovement(Monster monster, List<MoveElement> elements) {
        for (MoveElement e : elements) {
            switch (e) {
                case AbsoluteMove m -> {
                    monster.setPosition(new Point(m.x(), m.y() - 2));
                    monster.setStance(m.stance());
                }
                case RelativeMove m -> monster.setStance(m.stance());
                case TeleportMove t when t.command() == 11 -> monster.setStance(t.stance());
                case TeleportMove t -> {
                    monster.setPosition(new Point(t.x(), t.y() - 2));
                    monster.setStance(t.stance());
                }
                case JumpDownMove j -> {
                    monster.setPosition(new Point(j.x(), j.y() - 2));
                    monster.setStance(j.stance());
                }
                default -> {
                }
            }
        }
    }

    /** MoveLifeHandler 原样迁移的历史 quirk：`0xFF >> 1` 因优先级实为 0x7F，语义即 pVal >= pMin。 */
    private static boolean inRangeInclusive(byte pVal, int pMin, int pMax) {
        return !(pVal < pMin) || (pVal > pMax);
    }


    public final void toggleEnvironment(final String ms) {
        Map<String, Integer> env = getEnvironment();

        if (env.containsKey(ms)) {
            moveEnvironment(ms, env.get(ms) == 1 ? 2 : 1);
        } else {
            moveEnvironment(ms, 1);
        }
    }

    public final void moveEnvironment(final String ms, final int type) {
        broadcastMessage(PacketCreator.environmentMove(ms, type));

        objectWLock.lock();
        try {
            environment.put(ms, type);
        } finally {
            objectWLock.unlock();
        }
    }

    public final Map<String, Integer> getEnvironment() {
        objectRLock.lock();
        try {
            return Collections.unmodifiableMap(environment);
        } finally {
            objectRLock.unlock();
        }
    }

    public String getMapName() {
        return st.mapName();
    }

    public String getStreetName() {
        return st.streetName();
    }

    public boolean hasClock() {
        return st.clock();
    }

    public boolean isTown() {
        return st.town();
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void setMuted(boolean mute) {
        isMuted = mute;
    }

    public boolean getEverlast() {
        return st.everlast();
    }

    public int getSpawnedMonstersOnMap() {
        return spawnedMonstersOnMap.get();
    }

    // not really costly to keep generating imo
    public void sendNightEffect(CharacterRef chr) {
        for (Entry<Integer, Integer> types : st.backgroundTypes().entrySet()) {
            if (types.getValue() >= 3) { // 3 is a special number
                chr.sendPacket(PacketCreator.changeBackgroundEffect(true, types.getKey(), 0));
            }
        }
    }

    public void broadcastNightEffect() {
        chrRLock.lock();
        try {
            Iterator<CharacterRef> iterator = characters.iterator();
            while (iterator.hasNext()) {
                CharacterRef chr = iterator.next();
                if (chrDisconnected(iterator, chr)) {
                    continue;
                }
                sendNightEffect(chr);
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public CharacterRef getCharacterByName(String name) {
        chrRLock.lock();
        try {
            for (CharacterRef cr : this.characters) {
                CharacterRef chr = cr;
                if (chr.getName().equalsIgnoreCase(name)) {
                    return chr;
                }
            }
        } finally {
            chrRLock.unlock();
        }
        return null;
    }

    public boolean makeDisappearItemFromMap(MapObject mapobj) {
        if (mapobj instanceof MapItem) {
            return makeDisappearItemFromMap((MapItem) mapobj);
        } else {
            return mapobj == null;  // no drop to make disappear...
        }
    }

    public boolean makeDisappearItemFromMap(MapItem mapitem) {
        if (mapitem != null && mapitem == getMapObject(mapitem.getObjectId())) {
            mapitem.lockItem();
            try {
                if (mapitem.isPickedUp()) {
                    return true;
                }

                MapleMap.this.pickItemDrop(PacketCreator.removeItemFromMap(mapitem.getObjectId(), 0, 0), mapitem);
                return true;
            } finally {
                mapitem.unlockItem();
            }
        }

        return false;
    }

    private class MobLootEntry implements Runnable {

        private final byte droptype;
        private final int mobpos;
        private final double chRate;
        private final Point pos;
        private final List<MonsterDropEntry> dropEntry;
        private final List<List<MonsterDropEntry>> questGroups;   // 任务段序列（段序 = 掉落序，段内 shuffle）
        private final List<MonsterGlobalDropEntry> globalEntry;
        private final CharacterRef chr;
        private final Monster mob;
        private final Battle.DropEntitlement ent;   // null = legacy 路径（非普攻死亡）

        protected MobLootEntry(byte droptype, int mobpos, double chRate, Point pos, List<MonsterDropEntry> dropEntry, List<List<MonsterDropEntry>> questGroups, List<MonsterGlobalDropEntry> globalEntry, CharacterRef chr, Monster mob, Battle.DropEntitlement ent) {
            this.droptype = droptype;
            this.mobpos = mobpos;
            this.chRate = chRate;
            this.pos = pos;
            this.dropEntry = dropEntry;
            this.questGroups = questGroups;
            this.globalEntry = globalEntry;
            this.chr = chr;
            this.mob = mob;
            this.ent = ent;
        }

        @Override
        public void run() {
            byte d = 1;

            // Normal Drops
            d = dropItemsFromMonsterOnMap(dropEntry, pos, d, chRate, droptype, mobpos, ent, chr, mob);

            // Global Drops
            d = dropGlobalItemsFromMonsterOnMap(globalEntry, pos, d, droptype, mobpos, chr, mob);

            // Quest Drops（legacy: visible/other；ent: mvp 需求/其他攻击者需求/无人需求）
            for (List<MonsterDropEntry> group : questGroups) {
                d = dropItemsFromMonsterOnMap(group, pos, d, chRate, droptype, mobpos, ent, chr, mob);
            }
        }
    }

    private class ActivateItemReactor implements Runnable {

        private final MapItem mapitem;
        private final Reactor reactor;
        private final Client c;

        public ActivateItemReactor(MapItem mapitem, Reactor reactor, Client c) {
            this.mapitem = mapitem;
            this.reactor = reactor;
            this.c = c;
        }

        @Override
        public void run() {
            reactor.hitLockReactor();
            try {
                if (reactor.getReactorType() == 100) {
                    if (reactor.getShouldCollect() == true && mapitem != null && mapitem == getMapObject(mapitem.getObjectId())) {
                        mapitem.lockItem();
                        try {
                            if (mapitem.isPickedUp()) {
                                return;
                            }
                            mapitem.setPickedUp(true);
                            unregisterItemDrop(mapitem);

                            reactor.setShouldCollect(false);
                            MapleMap.this.broadcastMessage(PacketCreator.removeItemFromMap(mapitem.getObjectId(), 0, 0), mapitem.getPosition());

                            droppedItemCount.decrementAndGet();
                            MapleMap.this.removeMapObject(mapitem);

                            reactor.hitReactor(c);

                            if (reactor.getDelay() > 0) {
                                MapleMap reactorMap = reactor.getMap();

                                OverallService service = (OverallService) reactorMap.getChannelServer().getServiceAccess(ChannelServices.OVERALL);
                                service.registerOverallAction(reactorMap.getId(), () -> {
                                    reactor.lockReactor();
                                    try {
                                        reactor.resetReactorActions(0);
                                        reactor.setAlive(true);
                                        broadcastMessage(PacketCreator.triggerReactor(reactor, 0));
                                    } finally {
                                        reactor.unlockReactor();
                                    }
                                }, reactor.getDelay());
                            }
                        } finally {
                            mapitem.unlockItem();
                        }
                    }
                }
            } finally {
                reactor.hitUnlockReactor();
            }
        }
    }

    public void instanceMapFirstSpawn(int difficulty, boolean isPq) {
        for (SpawnPoint spawnPoint : getAllMonsterSpawn()) {
            if (spawnPoint.getMobTime() == -1) {   //just those allowed to be spawned only once
                spawnMonster(spawnPoint.getMonster());
            }
        }
    }

    public void instanceMapRespawn() {
        if (!allowSummons) {
            return;
        }

        final int numShouldSpawn = (short) ((monsterSpawn.size() - spawnedMonstersOnMap.get()));//Fking lol'd
        if (numShouldSpawn > 0) {
            List<SpawnPoint> randomSpawn = getMonsterSpawn();
            Collections.shuffle(randomSpawn);
            int spawned = 0;
            for (SpawnPoint spawnPoint : randomSpawn) {
                if (spawnPoint.shouldSpawn()) {
                    spawnMonster(spawnPoint.getMonster());
                    spawned++;
                    if (spawned >= numShouldSpawn) {
                        break;
                    }
                }
            }
        }
    }

    public void instanceMapForceRespawn() {
        if (!allowSummons) {
            return;
        }

        final int numShouldSpawn = (short) ((monsterSpawn.size() - spawnedMonstersOnMap.get()));//Fking lol'd
        if (numShouldSpawn > 0) {
            List<SpawnPoint> randomSpawn = getMonsterSpawn();
            Collections.shuffle(randomSpawn);
            int spawned = 0;
            for (SpawnPoint spawnPoint : randomSpawn) {
                if (spawnPoint.shouldForceSpawn()) {
                    spawnMonster(spawnPoint.getMonster());
                    spawned++;
                    if (spawned >= numShouldSpawn) {
                        break;
                    }
                }
            }
        }
    }

    public void closeMapSpawnPoints() {
        for (SpawnPoint spawnPoint : getMonsterSpawn()) {
            spawnPoint.setDenySpawn(true);
        }
    }

    public void restoreMapSpawnPoints() {
        for (SpawnPoint spawnPoint : getMonsterSpawn()) {
            spawnPoint.setDenySpawn(false);
        }
    }

    public void setAllowSpawnPointInBox(boolean allow, Rectangle box) {
        for (SpawnPoint sp : getMonsterSpawn()) {
            if (box.contains(sp.getPosition())) {
                sp.setDenySpawn(!allow);
            }
        }
    }

    public void setAllowSpawnPointInRange(boolean allow, Point from, double rangeSq) {
        for (SpawnPoint sp : getMonsterSpawn()) {
            if (from.distanceSq(sp.getPosition()) <= rangeSq) {
                sp.setDenySpawn(!allow);
            }
        }
    }

    public SpawnPoint findClosestSpawnpoint(Point from) {
        SpawnPoint closest = null;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (SpawnPoint sp : getMonsterSpawn()) {
            double distance = sp.getPosition().distanceSq(from);
            if (distance < shortestDistance) {
                closest = sp;
                shortestDistance = distance;
            }
        }
        return closest;
    }

    private static double getCurrentSpawnRate(int numPlayers) {
        return 0.70 + (0.05 * Math.min(6, numPlayers));
    }

    private int getNumShouldSpawn(int numPlayers) {
        /*
        System.out.println("----------------------------------");
        for (SpawnPoint spawnPoint : getMonsterSpawn()) {
            System.out.println("sp " + spawnPoint.getPosition().getX() + ", " + spawnPoint.getPosition().getY() + ": " + spawnPoint.getDenySpawn());
        }
        System.out.println("try " + monsterSpawn.size() + " - " + spawnedMonstersOnMap.get());
        System.out.println("----------------------------------");
        */

        if (GameConfig.getServerBoolean("use_enable_full_respawn")) {
            return (monsterSpawn.size() - spawnedMonstersOnMap.get());
        }

        int maxNumShouldSpawn = (int) Math.ceil(getCurrentSpawnRate(numPlayers) * monsterSpawn.size());
        return maxNumShouldSpawn - spawnedMonstersOnMap.get();
    }

    public void respawn() {
        if (!allowSummons) {
            return;
        }

        int numPlayers;
        chrRLock.lock();
        try {
            numPlayers = characters.size();

            if (numPlayers == 0) {
                return;
            }
        } finally {
            chrRLock.unlock();
        }

        int numShouldSpawn = getNumShouldSpawn(numPlayers);
        if (numShouldSpawn > 0) {
            List<SpawnPoint> randomSpawn = new ArrayList<>(getMonsterSpawn());
            Collections.shuffle(randomSpawn);
            short spawned = 0;
            for (SpawnPoint spawnPoint : randomSpawn) {
                if (spawnPoint.shouldSpawn()) {
                    spawnMonster(spawnPoint.getMonster());
                    spawned++;

                    if (spawned >= numShouldSpawn) {
                        break;
                    }
                }
            }
        }
    }

    public void mobMpRecovery() {
        for (Monster mob : this.getAllMonsters()) {
            if (mob.isAlive()) {
                mob.heal(0, mob.getLevel());
            }
        }
    }

    public List<Rectangle> getAreas() {
        return st.areas();
    }

    public Rectangle getArea(int index) {
        return st.areas().get(index);
    }

    public final int getNumPlayersInArea(final int index) {
        return getNumPlayersInRect(getArea(index));
    }

    public final int getNumPlayersInRect(final Rectangle rect) {
        int ret = 0;

        chrRLock.lock();
        try {
            final Iterator<CharacterRef> ltr = characters.iterator();
            while (ltr.hasNext()) {
                if (rect.contains(ltr.next().getPosition())) {
                    ret++;
                }
            }
        } finally {
            chrRLock.unlock();
        }
        return ret;
    }

    public final int getNumPlayersItemsInArea(final int index) {
        return getNumPlayersItemsInRect(getArea(index));
    }

    public final int getNumPlayersItemsInRect(final Rectangle rect) {
        int retP = getNumPlayersInRect(rect);
        int retI = getMapObjectsInBox(rect, Arrays.asList(MapObjectType.ITEM)).size();

        return retP + retI;
    }

    public int getHPDec() {
        return st.decHP();
    }

    public int getHPDecProtect() {
        return st.protectItem();
    }

    public float getRecovery() {
        return st.recovery();
    }

    public void setDocked(boolean isDocked) {
        this.docked = isDocked;
    }

    public boolean getDocked() {
        return this.docked;
    }

    public int getSeats() {
        return st.seats();
    }

    public void broadcastGMMessage(CharacterRef source, Packet packet, boolean repeatToSource) {
        broadcastGMMessage(repeatToSource ? null : source, packet, Double.POSITIVE_INFINITY, source.getPosition());
    }

    private void broadcastGMMessage(CharacterRef source, Packet packet, double rangeSq, Point rangedFrom) {
        chrRLock.lock();
        try {
            Iterator<CharacterRef> iterator = characters.iterator();
            while (iterator.hasNext()) {
                CharacterRef chr = iterator.next();
                if (chrDisconnected(iterator, chr)) {
                    continue;
                }
                if (source == null || chr != source && chr.isGM()) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.sendPacket(packet);
                        }
                    } else {
                        chr.sendPacket(packet);
                    }
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public void broadcastNONGMMessage(CharacterRef source, Packet packet, boolean repeatToSource) {
        chrRLock.lock();
        try {
            Iterator<CharacterRef> iterator = characters.iterator();
            while (iterator.hasNext()) {
                CharacterRef chr = iterator.next();
                if (chrDisconnected(iterator, chr)) {
                    continue;
                }
                if (source == null || chr != source && !chr.isGM()) {
                    chr.sendPacket(packet);
                }
            }
        } finally {
            chrRLock.unlock();
        }
    }

    public OxQuiz getOx() {
        return ox;
    }

    public void setOx(OxQuiz set) {
        this.ox = set;
    }

    public void setOxQuiz(boolean b) {
        this.isOxQuiz = b;
    }

    public boolean isOxQuiz() {
        return isOxQuiz;
    }

    public String getOnUserEnter() {
        return st.onUserEnter();
    }

    public String getOnFirstUserEnter() {
        return st.onFirstUserEnter();
    }

    public void clearDrops(CharacterRef player) {
        for (MapObject i : getMapObjectsInRange(player.getPosition(), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.ITEM))) {
            droppedItemCount.decrementAndGet();
            removeMapObject(i);
            this.broadcastMessage(PacketCreator.removeItemFromMap(i.getObjectId(), 0, player.getId()));
        }
    }

    public void clearDrops() {
        for (MapObject i : getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.ITEM))) {
            droppedItemCount.decrementAndGet();
            removeMapObject(i);
            this.broadcastMessage(PacketCreator.removeItemFromMap(i.getObjectId(), 0, 0));
        }
    }

    public int getFieldLimit() {
        return st.fieldLimit();
    }

    public void allowSummonState(boolean b) {
        MapleMap.this.allowSummons = b;
    }

    public boolean getSummonState() {
        return MapleMap.this.allowSummons;
    }

    public void warpEveryone(int to) {
        List<CharacterRef> players = new ArrayList<>(getCharacters());

        for (CharacterRef chr : players) {
            chr.changeMap(to);
        }
    }

    public void warpEveryone(int to, int pto) {
        List<CharacterRef> players = new ArrayList<>(getCharacters());

        for (CharacterRef chr : players) {
            chr.changeMap(to, pto);
        }
    }

    // BEGIN EVENTS
    public void setSnowball(int team, Snowball ball) {
        switch (team) {
            case 0:
                this.snowball0 = ball;
                break;
            case 1:
                this.snowball1 = ball;
                break;
            default:
                break;
        }
    }

    public Snowball getSnowball(int team) {
        switch (team) {
            case 0:
                return snowball0;
            case 1:
                return snowball1;
            default:
                return null;
        }
    }

    public void setCoconut(Coconut nut) {
        this.coconut = nut;
    }

    public Coconut getCoconut() {
        return coconut;
    }

    public void warpOutByTeam(int team, int mapId) {
        List<CharacterRef> chars = new ArrayList<>(getCharacters());
        for (CharacterRef chr : chars) {
            if (chr != null) {
                if (chr.getTeam() == team) {
                    chr.changeMap(mapId);
                }
            }
        }
    }

    public void startEvent(final CharacterRef chr) {
        if (st.mapid() == MapId.EVENT_COCONUT_HARVEST && getCoconut() == null) {
            setCoconut(new Coconut(this));
            coconut.startEvent();
        } else if (st.mapid() == MapId.EVENT_PHYSICAL_FITNESS) {
            chr.setFitness(new Fitness(chr.unref()));
            chr.getFitness().startFitness();
        } else if (st.mapid() == MapId.EVENT_OLA_OLA_1 || st.mapid() == MapId.EVENT_OLA_OLA_2 ||
                st.mapid() == MapId.EVENT_OLA_OLA_3 || st.mapid() == MapId.EVENT_OLA_OLA_4) {
            chr.setOla(new Ola(chr.unref()));
            chr.getOla().startOla();
        } else if (st.mapid() == MapId.EVENT_OX_QUIZ && getOx() == null) {
            setOx(new OxQuiz(this));
            getOx().sendQuestion();
            setOxQuiz(true);
        } else if (st.mapid() == MapId.EVENT_SNOWBALL && getSnowball(chr.getTeam()) == null) {
            setSnowball(0, new Snowball(0, this));
            setSnowball(1, new Snowball(1, this));
            getSnowball(chr.getTeam()).startEvent();
        }
    }

    public boolean eventStarted() {
        return eventstarted;
    }

    public void startEvent() {
        this.eventstarted = true;
    }

    public void setEventStarted(boolean event) {
        this.eventstarted = event;
    }

    public String getEventNPC() {
        StringBuilder sb = new StringBuilder();
        sb.append("请与 "+ st.mapName() + " 的 ");
        if (st.mapid() == MapId.SOUTHPERRY) {
            sb.append("珀尔");
        } else if (st.mapid() == MapId.LITH_HARBOUR) {
            sb.append("江");
        } else if (st.mapid() == MapId.ORBIS) {
            sb.append("马丁");
        } else if (st.mapid() == MapId.LUDIBRIUM) {
            sb.append("托尼");
        } else {
            return null;
        }
        sb.append(" 进行对话。");
        return sb.toString();
    }

    public boolean hasEventNPC() {
        return st.mapid() == 60000 || st.mapid() == MapId.LITH_HARBOUR || st.mapid() == MapId.ORBIS || st.mapid() == MapId.LUDIBRIUM;
    }

    public boolean isStartingEventMap() {
        return st.mapid() == MapId.EVENT_PHYSICAL_FITNESS || st.mapid() == MapId.EVENT_OX_QUIZ ||
                st.mapid() == MapId.EVENT_FIND_THE_JEWEL || st.mapid() == MapId.EVENT_OLA_OLA_0 || st.mapid() == MapId.EVENT_OLA_OLA_1;
    }

    public boolean isEventMap() {
        return st.mapid() >= MapId.EVENT_FIND_THE_JEWEL && st.mapid() < MapId.EVENT_WINNER || st.mapid() > MapId.EVENT_EXIT && st.mapid() <= 109090000;
    }

    public Pair<Integer, String> getTimeMob() {
        return st.timeMob();
    }

    public void toggleHiddenNPC(int id) {
        chrRLock.lock();
        objectRLock.lock();
        try {
            for (MapObject obj : mapobjects.values()) {
                if (obj.getType() == MapObjectType.NPC) {
                    NPC npc = (NPC) obj;
                    if (npc.getId() == id) {
                        npc.setHide(!npc.isHidden());
                        if (!npc.isHidden()) //Should only be hidden upon changing maps
                        {
                            broadcastMessage(PacketCreator.spawnNPC(npc));
                        }
                    }
                }
            }
        } finally {
            objectRLock.unlock();
            chrRLock.unlock();
        }
    }

    public short getMobInterval() {
        return st.mobInterval();
    }

    public void clearMapObjects() {
        clearDrops();
        killAllMonsters();
        resetReactors();
    }

    public final void resetFully() {
        resetMapObjects();
    }

    public void resetMapObjects() {
        resetMapObjects(1, false);
    }

    public void resetPQ() {
        resetPQ(1);
    }

    public void resetPQ(int difficulty) {
        resetMapObjects(difficulty, true);
    }

    public void resetMapObjects(int difficulty, boolean isPq) {
        clearMapObjects();

        restoreMapSpawnPoints();
        instanceMapFirstSpawn(difficulty, isPq);
    }

    public void broadcastShip(final boolean state) {
        broadcastMessage(PacketCreator.boatPacket(state));
        this.setDocked(state);
    }

    public void broadcastEnemyShip(final boolean state) {
        broadcastMessage(PacketCreator.crogBoatPacket(state));
        this.setDocked(state);
    }

    public boolean isHorntailDefeated() {   // all parts of dead horntail can be found here?
        for (int i = MobId.DEAD_HORNTAIL_MIN; i <= MobId.DEAD_HORNTAIL_MAX; i++) {
            if (getMonsterById(i) == null) {
                return false;
            }
        }

        return true;
    }

    public void spawnHorntailOnGroundBelow(final Point targetPoint) {   // ayy lmao
        Monster htIntro = LifeFactory.getMonster(MobId.SUMMON_HORNTAIL);
        spawnMonsterOnGroundBelow(htIntro, targetPoint);    // htintro spawn animation converting into horntail detected thanks to Arnah

        final Monster ht = LifeFactory.getMonster(MobId.HORNTAIL);
        ht.setParentMobOid(htIntro.getObjectId());
        ht.addListener(new MonsterListener() {
            @Override
            public void monsterKilled(int aniTime) {
            }

            @Override
            public void monsterDamaged(CharacterRef from, int trueDmg) {
                ht.addHp(trueDmg);
            }

            @Override
            public void monsterHealed(int trueHeal) {
                ht.addHp(-trueHeal);
            }
        });
        spawnMonsterOnGroundBelow(ht, targetPoint);

        for (int mobId = MobId.HORNTAIL_HEAD_A; mobId <= MobId.HORNTAIL_TAIL; mobId++) {
            Monster m = LifeFactory.getMonster(mobId);
            m.setParentMobOid(htIntro.getObjectId());

            m.addListener(new MonsterListener() {
                @Override
                public void monsterKilled(int aniTime) {
                }

                @Override
                public void monsterDamaged(CharacterRef from, int trueDmg) {
                    // thanks Halcyon for noticing HT not dropping loots due to propagated damage not registering attacker
                    ht.applyFakeDamage(from, trueDmg, true);
                }

                @Override
                public void monsterHealed(int trueHeal) {
                    ht.addHp(trueHeal);
                }
            });

            spawnMonsterOnGroundBelow(m, targetPoint);
        }
    }

    private final List<Point> takenSpawns = new LinkedList<>();
    private final List<GuardianSpawnPoint> guardianSpawns = new LinkedList<>();
    private final List<MCSkill> blueTeamBuffs = new ArrayList<>();
    private final List<MCSkill> redTeamBuffs = new ArrayList<>();

    public List<MCSkill> getBlueTeamBuffs() {
        return blueTeamBuffs;
    }

    public List<MCSkill> getRedTeamBuffs() {
        return redTeamBuffs;
    }

    public void clearBuffList() {
        redTeamBuffs.clear();
        blueTeamBuffs.clear();
    }

    public List<MapObject> getAllPlayer() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.PLAYER));
    }

    public boolean isCPQMap() {
        switch (this.getId()) {
            case 980000101:
            case 980000201:
            case 980000301:
            case 980000401:
            case 980000501:
            case 980000601:
            case 980031100:
            case 980032100:
            case 980033100:
                return true;
        }
        return false;
    }

    public boolean isCPQMap2() {
        switch (this.getId()) {
            case 980031100:
            case 980032100:
            case 980033100:
                return true;
        }
        return false;
    }

    public boolean isCPQLobby() {
        switch (this.getId()) {
            case 980000100:
            case 980000200:
            case 980000300:
            case 980000400:
            case 980000500:
            case 980000600:
                return true;
        }
        return false;
    }

    public boolean isBlueCPQMap() {
        switch (this.getId()) {
            case 980000501:
            case 980000601:
            case 980031200:
            case 980032200:
            case 980033200:
                return true;
        }
        return false;
    }

    public boolean isPurpleCPQMap() {
        switch (this.getId()) {
            case 980000301:
            case 980000401:
            case 980031200:
            case 980032200:
            case 980033200:
                return true;
        }
        return false;
    }

    public Point getRandomSP(int team) {
        if (takenSpawns.size() > 0) {
            for (SpawnPoint sp : monsterSpawn) {
                for (Point pt : takenSpawns) {
                    if ((sp.getPosition().x == pt.x && sp.getPosition().y == pt.y) || (sp.getTeam() != team && !this.isBlueCPQMap())) {
                        continue;
                    } else {
                        takenSpawns.add(pt);
                        return sp.getPosition();
                    }
                }
            }
        } else {
            for (SpawnPoint sp : monsterSpawn) {
                if (sp.getTeam() == team || this.isBlueCPQMap()) {
                    takenSpawns.add(sp.getPosition());
                    return sp.getPosition();
                }
            }
        }
        return null;
    }

    public GuardianSpawnPoint getRandomGuardianSpawn(int team) {
        boolean alltaken = true;
        for (GuardianSpawnPoint a : this.guardianSpawns) {
            if (!a.isTaken()) {
                alltaken = false;
                break;
            }
        }
        if (alltaken) {
            return null;
        }
        if (!this.guardianSpawns.isEmpty()) {
            while (true) {
                for (GuardianSpawnPoint gsp : this.guardianSpawns) {
                    if (!gsp.isTaken() && Math.random() < 0.3 && (gsp.getTeam() == -1 || gsp.getTeam() == team)) {
                        return gsp;
                    }
                }
            }
        }
        return null;
    }

    public void addGuardianSpawnPoint(GuardianSpawnPoint a) {
        this.guardianSpawns.add(a);
    }

    public int spawnGuardian(int team, int num) {
        try {
            if (team == 0 && redTeamBuffs.size() >= 4 || team == 1 && blueTeamBuffs.size() >= 4) {
                return 2;
            }
            final MCSkill skill = CarnivalFactory.getInstance().getGuardian(num);
            if (team == 0 && redTeamBuffs.contains(skill)) {
                return 0;
            } else if (team == 1 && blueTeamBuffs.contains(skill)) {
                return 0;
            }
            GuardianSpawnPoint pt = this.getRandomGuardianSpawn(team);
            if (pt == null) {
                return -1;
            }
            int reactorID = 9980000 + team;
            Reactor reactor = new Reactor(ReactorFactory.getReactorS(reactorID), reactorID);
            pt.setTaken(true);
            reactor.setPosition(pt.getPosition());
            reactor.setName(team + "" + num); //lol
            reactor.resetReactorActions(0);
            this.spawnReactor(reactor);
            reactor.setGuardian(pt);
            this.buffMonsters(team, skill);
            getReactorByOid(reactor.getObjectId()).hitReactor(((Character) this.getAllPlayer().get(0)).getClient());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 1;
    }

    public void buffMonsters(int team, MCSkill skill) {
        if (skill == null) {
            return;
        }

        if (team == 0) {
            redTeamBuffs.add(skill);
        } else if (team == 1) {
            blueTeamBuffs.add(skill);
        }
        for (MapObject mmo : this.mapobjects.values()) {
            if (mmo.getType() == MapObjectType.MONSTER) {
                Monster mob = (Monster) mmo;
                if (mob.getTeam() == team) {
                    skill.getSkill().applyEffect(null, mob, false, null);
                }
            }
        }
    }

    public final List<Integer> getSkillIds() {
        return st.skillIds();
    }

    public final List<Pair<Integer, Integer>> getMobsToSpawn() {
        return st.mobsToSpawn();
    }

    public boolean isCPQWinnerMap() {
        switch (this.getId()) {
            case 980000103:
            case 980000203:
            case 980000303:
            case 980000403:
            case 980000503:
            case 980000603:
            case 980031300:
            case 980032300:
            case 980033300:
                return true;
        }
        return false;
    }

    public boolean isCPQLoserMap() {
        switch (this.getId()) {
            case 980000104:
            case 980000204:
            case 980000304:
            case 980000404:
            case 980000504:
            case 980000604:
            case 980031400:
            case 980032400:
            case 980033400:
                return true;
        }
        return false;
    }

    public void runCharacterStatUpdate() {
        if (!statUpdateRunnables.isEmpty()) {
            List<Runnable> toRun = new ArrayList<>(statUpdateRunnables);
            statUpdateRunnables.clear();

            for (Runnable r : toRun) {
                r.run();
            }
        }
    }

    public void registerCharacterStatUpdate(Runnable r) {
        statUpdateRunnables.add(r);
    }

    public void dispose() {
        for (Monster mm : this.getAllMonsters()) {
            mm.dispose();
        }

        clearMapObjects();

        event = null;
        // 静态内容随本实例一同不可达（不可变字段，不做置空释放）；动态注册表照常清理
        portals.clear();
        mapEffect = null;

        chrWLock.lock();
        try {
            aggroMonitor.dispose();
            aggroMonitor = null;

            if (itemMonitor != null) {
                itemMonitor.cancel(false);
                itemMonitor = null;
            }

            if (expireItemsTask != null) {
                expireItemsTask.cancel(false);
                expireItemsTask = null;
            }

            if (mobSpawnLootTask != null) {
                mobSpawnLootTask.cancel(false);
                mobSpawnLootTask = null;
            }

            if (characterStatUpdateTask != null) {
                characterStatUpdateTask.cancel(false);
                characterStatUpdateTask = null;
            }
        } finally {
            chrWLock.unlock();
        }
    }

    public int getMaxMobs() {
        return st.maxMobs();
    }

    public int getMaxReactors() {
        return st.maxReactors();
    }

    public int getDeathCP() {
        return st.deathCP();
    }

    public int getTimeDefault() {
        return st.timeDefault();
    }

    public int getTimeExpand() {
        return st.timeExpand();
    }

}
