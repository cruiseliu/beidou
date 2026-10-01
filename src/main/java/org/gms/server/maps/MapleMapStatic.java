package org.gms.server.maps;

import org.gms.constants.id.MapId;
import org.gms.util.Pair;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 地图静态内容（WZ 装载期决定，装载完成后不可变）：foothold 几何、地图属性、脚本名、
 * CPQ 参数等。{@link MapleMap} 与 {@link MapleMapRef} 持有同一实例——静态事实读取
 * 两侧均无锁直读（player strand 经 ref.statics()，不走 actor api）。
 *
 * <p><b>归组纪律</b>：按写入方分类——仅 MapFactory 装载期写入的字段入本类；任何运行时
 * 可变（含元素可变的容器，如 GuardianSpawnPoint 列表、portals 的状态位）留 MapleMap。
 * 深不可变：集合构造期拷贝为不可变视图；mapArea 的 Rectangle 按只读纪律共享（外部调用方
 * 已审计仅读字段）。
 *
 * <p><b>装配</b>：MapFactory 填 {@link Builder} → {@code build()}（内含 xLimits 落点
 * 边界计算，含跨图 dropBoundsCache）→ {@code new MapleMap(st)}。发布前构造完成，
 * final 字段保证安全发布。
 */
public final class MapleMapStatic {

    private static final Map<Integer, Pair<Integer, Integer>> dropBoundsCache = new HashMap<>(100);
    /** 仅 build() 期使用（装载串行），保留原 generateMapDropRangeCache 的锁形态 */
    private static final Lock bndLock = new ReentrantLock(true);

    // ── 标识 ──
    private final int mapid;
    private final int world;
    private final int channel;
    private final int returnMapId;
    private final byte monsterRate;

    // ── 几何 ──
    private final FootholdTree footholds;
    /** 掉落 x 边界（build() 期由 footholds+mapArea 二分计算，含跨图缓存） */
    private final Pair<Integer, Integer> xLimits;
    private final Rectangle mapArea;
    private final List<Rectangle> areas;

    // ── WZ 属性 ──
    private final Map<Integer, Integer> backgroundTypes;
    private final int seats;
    private final boolean clock;
    private final boolean boat;
    private final boolean town;
    private final boolean everlast;
    private final String mapName;
    private final String streetName;
    private final String onFirstUserEnter;
    private final String onUserEnter;
    private final int fieldType;
    private final int fieldLimit;
    private final int mobCapacity;
    private final int forcedReturnMap;
    private final int timeLimit;
    private final long mapTimer;
    private final int decHP;
    private final int protectItem;
    private final float recovery;
    private final short mobInterval;
    private final Pair<Integer, String> timeMob;

    // ── CPQ（WZ monsterCarnival 节点）──
    private final int maxMobs;
    private final int maxReactors;
    private final int deathCP;
    private final int timeDefault;
    private final int timeExpand;
    private final List<Integer> skillIds;
    private final List<Pair<Integer, Integer>> mobsToSpawn;

    // ── portal 静态半（WZ 装载期冻结；动态门禁归 map actor）──
    private final Map<String, PortalStatic> portals;
    private final Map<Integer, PortalStatic> portalsById;

    private MapleMapStatic(Builder b) {
        this.mapid = b.mapid;
        this.world = b.world;
        this.channel = b.channel;
        this.returnMapId = b.returnMapId;
        this.monsterRate = b.monsterRate;
        this.footholds = b.footholds;
        this.mapArea = b.mapArea;
        this.xLimits = computeXLimits();
        this.areas = List.copyOf(b.areas);
        this.backgroundTypes = Map.copyOf(b.backgroundTypes);
        this.seats = b.seats;
        this.clock = b.clock;
        this.boat = b.boat;
        this.town = b.town;
        this.everlast = b.everlast;
        this.mapName = b.mapName;
        this.streetName = b.streetName;
        this.onFirstUserEnter = b.onFirstUserEnter;
        this.onUserEnter = b.onUserEnter;
        this.fieldType = b.fieldType;
        this.fieldLimit = b.fieldLimit;
        this.mobCapacity = b.mobCapacity;
        this.forcedReturnMap = b.forcedReturnMap;
        this.timeLimit = b.timeLimit;
        this.mapTimer = 0L;
        this.decHP = b.decHP;
        this.protectItem = b.protectItem;
        this.recovery = b.recovery;
        this.mobInterval = b.mobInterval;
        this.timeMob = b.timeMob;
        this.maxMobs = b.maxMobs;
        this.maxReactors = b.maxReactors;
        this.deathCP = b.deathCP;
        this.timeDefault = b.timeDefault;
        this.timeExpand = b.timeExpand;
        this.skillIds = List.copyOf(b.skillIds);
        this.mobsToSpawn = List.copyOf(b.mobsToSpawn);
        // LinkedHashMap 保序（随机出生点选择遍历序确定）；Unmodifiable 防运行期改动
        this.portals = Collections.unmodifiableMap(new LinkedHashMap<>(b.portals));
        this.portalsById = Collections.unmodifiableMap(new LinkedHashMap<>(b.portalsById));
    }

    public int mapid() {
        return mapid;
    }

    public int world() {
        return world;
    }

    public int channel() {
        return channel;
    }

    public int returnMapId() {
        return returnMapId;
    }

    public byte monsterRate() {
        return monsterRate;
    }

    public FootholdTree footholds() {
        return footholds;
    }

    public Pair<Integer, Integer> xLimits() {
        return xLimits;
    }

    public Rectangle mapArea() {
        return mapArea;
    }

    public List<Rectangle> areas() {
        return areas;
    }

    public Map<Integer, Integer> backgroundTypes() {
        return backgroundTypes;
    }

    public int seats() {
        return seats;
    }

    public boolean clock() {
        return clock;
    }

    public boolean boat() {
        return boat;
    }

    public boolean town() {
        return town;
    }

    public boolean everlast() {
        return everlast;
    }

    public String mapName() {
        return mapName;
    }

    public String streetName() {
        return streetName;
    }

    public String onFirstUserEnter() {
        return onFirstUserEnter;
    }

    public String onUserEnter() {
        return onUserEnter;
    }

    public int fieldType() {
        return fieldType;
    }

    public int fieldLimit() {
        return fieldLimit;
    }

    public int mobCapacity() {
        return mobCapacity;
    }

    public int forcedReturnMap() {
        return forcedReturnMap;
    }

    public int timeLimit() {
        return timeLimit;
    }

    public long mapTimer() {
        return mapTimer;
    }

    public int decHP() {
        return decHP;
    }

    public int protectItem() {
        return protectItem;
    }

    public float recovery() {
        return recovery;
    }

    public short mobInterval() {
        return mobInterval;
    }

    public Pair<Integer, String> timeMob() {
        return timeMob;
    }

    public int maxMobs() {
        return maxMobs;
    }

    public int maxReactors() {
        return maxReactors;
    }

    public int deathCP() {
        return deathCP;
    }

    public int timeDefault() {
        return timeDefault;
    }

    public int timeExpand() {
        return timeExpand;
    }

    public List<Integer> skillIds() {
        return skillIds;
    }

    public List<Pair<Integer, Integer>> mobsToSpawn() {
        return mobsToSpawn;
    }

    // ── portal 静态半（移植 MapleMap 同名查询；纯 WZ 数据计算，player strand 无锁直读）──

    /** 按门名取（查无返回 null，对齐 MapleMap.getPortal 语义） */
    public PortalStatic portal(String name) {
        return portals.get(name);
    }

    /** 按门 id 取（查无返回 null） */
    public PortalStatic portal(int id) {
        return portalsById.get(id);
    }

    /** 随机玩家出生点（type 0..1 且无跨图目标；空表回 0 号门）——移植 MapleMap 同名 */
    public PortalStatic randomPlayerSpawnpoint() {
        List<PortalStatic> spawnPoints = new ArrayList<>();
        for (PortalStatic portal : portals.values()) {
            if (portal.type() >= 0 && portal.type() <= 1 && portal.targetMapId() == MapId.NONE) {
                spawnPoints.add(portal);
            }
        }
        return spawnPoints.isEmpty() ? portal(0) : spawnPoints.get(new Random().nextInt(spawnPoints.size()));
    }

    /** 距指定点最近的玩家出生点（type 0..1 且无跨图目标）——移植 MapleMap 同名 */
    public PortalStatic findClosestPlayerSpawnpoint(Point from) {
        PortalStatic closest = null;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (PortalStatic portal : portals.values()) {
            double distance = portal.position().distanceSq(from);
            if (portal.type() >= 0 && portal.type() <= 1 && distance < shortestDistance && portal.targetMapId() == MapId.NONE) {
                closest = portal;
                shortestDistance = distance;
            }
        }
        return closest;
    }

    /** 距指定点最近的门（全类型）——移植 MapleMap 同名 */
    public PortalStatic findClosestPortal(Point from) {
        PortalStatic closest = null;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (PortalStatic portal : portals.values()) {
            double distance = portal.position().distanceSq(from);
            if (distance < shortestDistance) {
                closest = portal;
                shortestDistance = distance;
            }
        }
        return closest;
    }

    // ── mapid 分类（装载期 gating 与实例判定共用）──

    public static boolean isCPQMapId(int mapid) {
        switch (mapid) {
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

    public static boolean isCPQMap2Id(int mapid) {
        switch (mapid) {
            case 980031100:
            case 980032100:
            case 980033100:
                return true;
        }
        return false;
    }

    // ── 装载期计算 ──

    /**
     * 掉落 x 边界（原 MapleMap.generateMapDropRangeCache）：优先取跨图缓存，
     * 否则以 mapArea 左右端向内二分 foothold 求近似落点边界。
     */
    private Pair<Integer, Integer> computeXLimits() {
        bndLock.lock();
        try {
            Pair<Integer, Integer> bounds = dropBoundsCache.get(mapid);
            if (bounds != null) {
                return bounds;
            }
            // assuming MINIMAP always have an equal-greater picture representation of the map area
            // (players won't walk beyond the area known by the minimap).
            Point lp = new Point(mapArea.x, mapArea.y);
            Point rp = new Point(mapArea.x + mapArea.width, mapArea.y);
            Point fallback = new Point(mapArea.x + (mapArea.width / 2), mapArea.y);

            lp = bsearchDropPos(footholds, lp, fallback);  // approximated leftmost fh node position
            rp = bsearchDropPos(footholds, rp, fallback);  // approximated rightmost fh node position

            Pair<Integer, Integer> xl = new Pair<>(lp.x + 14, rp.x - 14);
            dropBoundsCache.put(mapid, xl);
            return xl;
        } finally {
            bndLock.unlock();
        }
    }

    static Point bsearchDropPos(FootholdTree footholds, Point initial, Point fallback) {
        Point res, dropPos = null;

        int awayx = fallback.x;
        int homex = initial.x;

        int y = initial.y - 85;

        do {
            int distx = awayx - homex;
            int dx = distx / 2;

            int searchx = homex + dx;
            if ((res = calcPointBelow(footholds, new Point(searchx, y))) != null) {
                awayx = searchx;
                dropPos = res;
            } else {
                homex = searchx;
            }
        } while (Math.abs(homex - awayx) > 5);

        return (dropPos != null) ? dropPos : fallback;
    }

    /** 落点几何（foothold 纯查询，runtime 与装载期共用；player 侧经 statics() 直调） */
    public static Point calcPointBelow(FootholdTree footholds, Point initial) {
        Foothold fh = footholds.findBelow(initial);
        if (fh == null) {
            return null;
        }
        int dropY = fh.getY1();
        if (!fh.isWall() && fh.getY1() != fh.getY2()) {
            double s1 = Math.abs(fh.getY2() - fh.getY1());
            double s2 = Math.abs(fh.getX2() - fh.getX1());
            double s5 = Math.cos(Math.atan(s2 / s1)) * (Math.abs(initial.x - fh.getX1()) / Math.cos(Math.atan(s1 / s2)));
            if (fh.getY2() < fh.getY1()) {
                dropY = fh.getY1() - (int) s5;
            } else {
                dropY = fh.getY1() + (int) s5;
            }
        }
        return new Point(initial.x, dropY);
    }

    /** WZ 装载期收集器（MapFactory 专用）；字段默认值对齐原 MapleMap 字段初始化 */
    public static final class Builder {
        private final int mapid;
        private final int world;
        private final int channel;
        private final int returnMapId;
        private final byte monsterRate;

        private FootholdTree footholds;
        private final Rectangle mapArea = new Rectangle();
        private final List<Rectangle> areas = new ArrayList<>();
        private final Map<Integer, Integer> backgroundTypes = new HashMap<>();
        private int seats;
        private boolean clock;
        private boolean boat;
        private boolean town;
        private boolean everlast;
        private String mapName;
        private String streetName;
        private String onFirstUserEnter;
        private String onUserEnter;
        private int fieldType;
        private int fieldLimit = 0;
        private int mobCapacity = -1;
        private int forcedReturnMap = org.gms.constants.id.MapId.NONE;
        private int timeLimit;
        private int decHP = 0;
        private int protectItem = 0;
        private float recovery = 1.0f;
        private short mobInterval = 5000;
        private Pair<Integer, String> timeMob;
        private int maxMobs;
        private int maxReactors;
        private int deathCP;
        private int timeDefault;
        private int timeExpand;
        private final List<Integer> skillIds = new ArrayList<>();
        private final List<Pair<Integer, Integer>> mobsToSpawn = new ArrayList<>();
        private final Map<String, PortalStatic> portals = new LinkedHashMap<>();
        private final Map<Integer, PortalStatic> portalsById = new LinkedHashMap<>();

        /** portal 静态半装配（WZ 冻结前调用；MapFactory 专用） */
        public Builder portals(Map<String, PortalStatic> byName, Map<Integer, PortalStatic> byId) {
            portals.putAll(byName);
            portalsById.putAll(byId);
            return this;
        }

        public Builder(int mapid, int world, int channel, int returnMapId, float monsterRate) {
            this.mapid = mapid;
            this.world = world;
            this.channel = channel;
            this.returnMapId = returnMapId;
            byte rate = (byte) Math.ceil(monsterRate);
            this.monsterRate = rate != 0 ? rate : 1;
        }

        public Builder onFirstUserEnter(String v) {
            this.onFirstUserEnter = v;
            return this;
        }

        public Builder onUserEnter(String v) {
            this.onUserEnter = v;
            return this;
        }

        public Builder fieldLimit(int v) {
            this.fieldLimit = v;
            return this;
        }

        public Builder mobInterval(short v) {
            this.mobInterval = v;
            return this;
        }

        public Builder timeMob(int id, String msg) {
            this.timeMob = new Pair<>(id, msg);
            return this;
        }

        public Builder mapPointBoundings(int px, int py, int h, int w) {
            this.mapArea.setBounds(px, py, w, h);
            return this;
        }

        public Builder mapLineBoundings(int vrTop, int vrBottom, int vrLeft, int vrRight) {
            this.mapArea.setBounds(vrLeft, vrTop, vrRight - vrLeft, vrBottom - vrTop);
            return this;
        }

        public Builder footholds(FootholdTree v) {
            this.footholds = v;
            return this;
        }

        public Builder addArea(Rectangle rec) {
            this.areas.add(rec);
            return this;
        }

        public Builder seats(int v) {
            this.seats = v;
            return this;
        }

        public Builder deathCP(int v) {
            this.deathCP = v;
            return this;
        }

        public Builder maxMobs(int v) {
            this.maxMobs = v;
            return this;
        }

        public Builder timeDefault(int v) {
            this.timeDefault = v;
            return this;
        }

        public Builder timeExpand(int v) {
            this.timeExpand = v;
            return this;
        }

        public Builder maxReactors(int v) {
            this.maxReactors = v;
            return this;
        }

        public Builder addSkillId(int v) {
            this.skillIds.add(v);
            return this;
        }

        public Builder addMobSpawn(int mobId, int spendCP) {
            this.mobsToSpawn.add(new Pair<>(mobId, spendCP));
            return this;
        }

        public Builder mapName(String v) {
            this.mapName = v;
            return this;
        }

        public Builder streetName(String v) {
            this.streetName = v;
            return this;
        }

        public Builder clock(boolean v) {
            this.clock = v;
            return this;
        }

        public Builder everlast(boolean v) {
            this.everlast = v;
            return this;
        }

        public Builder town(boolean v) {
            this.town = v;
            return this;
        }

        public Builder hpDec(int v) {
            this.decHP = v;
            return this;
        }

        public Builder hpDecProtect(int v) {
            this.protectItem = v;
            return this;
        }

        public Builder forcedReturnMap(int v) {
            this.forcedReturnMap = v;
            return this;
        }

        public Builder boat(boolean v) {
            this.boat = v;
            return this;
        }

        public Builder timeLimit(int v) {
            this.timeLimit = v;
            return this;
        }

        public Builder fieldType(int v) {
            this.fieldType = v;
            return this;
        }

        public Builder mobCapacity(int v) {
            this.mobCapacity = v;
            return this;
        }

        public Builder recovery(float v) {
            this.recovery = v;
            return this;
        }

        public Builder backgroundTypes(Map<Integer, Integer> v) {
            this.backgroundTypes.putAll(v);
            return this;
        }

        public MapleMapStatic build() {
            if (footholds == null) {
                throw new IllegalStateException("map " + mapid + ": footholds not set");
            }
            return new MapleMapStatic(this);
        }
    }
}
