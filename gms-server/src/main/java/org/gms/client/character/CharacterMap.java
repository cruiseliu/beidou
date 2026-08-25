package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.config.GameConfig;
import org.gms.constants.id.MapId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.packet.Packet;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyOperation;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.Trade;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.I18nUtil;
import org.gms.util.Locks;
import org.gms.util.Pair;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * 地图模块组件：当前地图（map/mapId）+ 换图状态 + 换图流程（changeMap 系列）+ 地图历史 + 放逐（banish）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getMap/getMapId/changeMap/... 对外转发）。
 *
 * 边界：只承载地图管理语义——当前图状态、换图（warp）、地图历史、放逐（banish 城镇卷轴/怪物放逐）。
 * 换图流程中编排的其他模块（party/Trade/chair/event/summons 等）经 owner 门面调用；
 * partyOperationUpdate（组队地图协作）属 party 语义，留在 Character。
 */
class CharacterMap {
    private static final Logger log = LoggerFactory.getLogger(CharacterMap.class);

    private final Character owner;

    /** 当前地图对象 */
    MapleMap map;

    /** 当前地图 id（map 为 null 时的兜底；换图前预置） */
    int mapId;

    /** 玩家客户端当前正在尝试更改地图或登录游戏地图 */
    private final AtomicBoolean mapTransitioning = new AtomicBoolean(true);

    /** 同一时刻只允许一个 warp 生效 */
    private boolean canWarpMap = true;
    /** warpAhead 预置的下一目标（changeMapInternal 末尾消费） */
    private int newWarpMap = -1;
    /** 嵌套 warp 计数（changeMap 内部再触发 changeMap 时） */
    private int canWarpCounter = 0;

    /** 最近访问地图历史（换图/回城用） */
    private final LinkedList<WeakReference<MapleMap>> lastVisitedMaps = new LinkedList<>();
    /** 地图历史锁 */
    private final Lock mapHistoryLock = new ReentrantLock(true);

    /** 放逐前位置记录（回城卷轴/怪物放逐写入，防放逐卷轴读取恢复） */
    private int banishMap = -1;
    private int banishSp = -1;
    private long banishTime = 0;

    CharacterMap(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    MapleMap getMap() {
        return map;
    }

    int getMapId() {
        if (map != null) {
            return map.getId();
        }
        return mapId;
    }

    void setMap(MapleMap map) {
        this.map = map;
    }

    void setMap(int PmapId) {
        this.mapId = PmapId;
    }

    void setMapId(int mapId) {
        this.mapId = mapId;
    }

    /**
     * 获取地图类
     * @param mapid 地图ID
     * @param showMsg   true = 地图不存在弹出提示，false = 不提示
     * @return
     */
    MapleMap getMap(int mapid, boolean showMsg) {
        /** 按 id 取地图（带错误提示）；null 且 showMsg 时告警 */
        MapleMap map = null;
        try {
            map = owner.getClient().getChannelServer().getMapFactory().getMap(mapid);
        } catch (Exception ignored) {
        }
        if (map == null && showMsg) {
            String msg = I18nUtil.getMessage("Character.Map.Change.message1", Integer.toString(mapid));
            owner.dropMessage(6, msg);
        }
        return map;
    }

    /** 取地图实例：事件图优先，其次怪物嘉年华图，最后普通地图工厂 */
    MapleMap getWarpMap(int map) {
        MapleMap warpMap;
        EventInstanceManager eim = owner.getEventInstance();
        if (eim != null) {
            warpMap = eim.getMapInstance(map);
        } else if (owner.getMonsterCarnival() != null && owner.getMonsterCarnival().getEventMap().getId() == map) {
            warpMap = owner.getMonsterCarnival().getEventMap();
        } else {
            warpMap = owner.getClient().getChannelServer().getMapFactory().getMap(map);
        }
        return warpMap;
    }

    /**
     * 玩家角色是否处于切换地图的状态
     * @return boolean
     */
    boolean isChangingMaps() {
        return this.mapTransitioning.get();
    }

    /**
     *  设置地图转换完成
     */
    void setMapTransitionComplete() {
        this.mapTransitioning.set(false);
    }

    // ── 换图 ──

    /** for use ONLY inside OnUserEnter map scripts that requires a player to change map while still moving between maps. */
    void warpAhead(int map) {
        newWarpMap = map;
    }

    void changeMap(int map) {
        changeMap(map, null);
    }

    /**
     * 玩家角色更改地图
     * @param mapid   地图ID
     */
    void changeMap(int map, Object pt) {
        MapleMap warpMap;
        EventInstanceManager eim = owner.getEventInstance();

        if (eim != null) {
            warpMap = eim.getMapInstance(map);
        } else {
            warpMap = getMap(map, true);
            if (warpMap == null) return; //判断地图不存在则直接返回并发送提示消息。
        }

        Portal portal = switch (pt) {
            case null -> warpMap.getRandomPlayerSpawnpoint();
            case Integer i -> warpMap.getPortal(i);
            case String s -> warpMap.getPortal(s);
            case Portal p -> p;
            default -> warpMap.getPortal(0);
        };
        changeMap(warpMap, portal);
    }

    void changeMap(MapleMap to) {
        changeMap(to, 0);
    }

    void changeMap(MapleMap to, int portal) {
        changeMap(to, to.getPortal(portal));
    }

    void changeMap(final MapleMap target, Portal pto) {
        canWarpCounter++;

        eventChangedMap(target.getId());    // player can be dropped from an event here, hence the new warping target.  //玩家可以从这里的事件中退出，因此成为新的扭曲目标。
        MapleMap to = getWarpMap(target.getId());
        if (pto == null) {
            pto = to.getPortal(0);
        }
        changeMapInternal(to, pto.getPosition(), PacketCreator.getWarpToMap(to, pto.getId(), owner));
        canWarpMap = false;

        canWarpCounter--;
        if (canWarpCounter == 0) {
            canWarpMap = true;
        }

        eventAfterChangedMap(getMapId());
    }

    void changeMap(final MapleMap target, final Point pos) {
        canWarpCounter++;

        eventChangedMap(target.getId());
        MapleMap to = getWarpMap(target.getId());
        changeMapInternal(to, pos, PacketCreator.getWarpToMap(to, 0x80, pos, owner));
        canWarpMap = false;

        canWarpCounter--;
        if (canWarpCounter == 0) {
            canWarpMap = true;
        }

        eventAfterChangedMap(getMapId());
    }

    void forceChangeMap(final MapleMap target, Portal pto) {
        // will actually enter the map given as parameter, regardless of being an eventmap or whatnot       //将实际输入作为参数给出的映射，无论是事件映射还是其他什么

        canWarpCounter++;
        eventChangedMap(MapId.NONE);

        EventInstanceManager mapEim = target.getEventInstance();
        if (mapEim != null) {
            EventInstanceManager playerEim = owner.getEventInstance();
            if (playerEim != null) {
                playerEim.exitPlayer(owner);
                if (playerEim.getPlayerCount() == 0) {
                    playerEim.dispose();
                }
            }

            // thanks Thora for finding an issue with players not being actually warped into the target event map (rather sent to the event starting map)
            //感谢Thora发现玩家实际上没有被扭曲到目标事件地图中（而是被发送到事件开始地图）的问题
            mapEim.registerPlayer(owner, false);
        }

        if (pto == null) {
            pto = target.getPortal(0);
        }
        changeMapInternal(target, pto.getPosition(), PacketCreator.getWarpToMap(target, pto.getId(), owner));
        canWarpMap = false;

        canWarpCounter--;
        if (canWarpCounter == 0) {
            canWarpMap = true;
        }

        eventAfterChangedMap(getMapId());
    }

    /** 是否带地图环境防护（寒冷/水下） */
    boolean buffMapProtection() {
        int thisMapid = mapId;
        int returnMapid = owner.getClient().getChannelServer().getMapFactory().getMap(thisMapid).getReturnMapId();

        // effLock/chrLock 已冗余：激活表为不可变快照，无锁迭代

        for (var mbs : owner.buffs.getActive().effects.entrySet()) {
            if (mbs.getKey() == EffectType.MAP_PROTECTION) {
                byte value = (byte) mbs.getValue().value;

                if (value == 1 && ((returnMapid == MapId.EL_NATH && thisMapid != MapId.ORBIS_TOWER_BOTTOM)
                        || returnMapid == MapId.INTERNET_CAFE)) {
                    return true;        //protection from cold
                } else {
                    return value == 2 && (returnMapid == MapId.AQUARIUM || thisMapid == MapId.ORBIS_TOWER_BOTTOM);        //breathing underwater
                }
            }
        }

        for (var it : owner.getInventory(InventoryType.EQUIPPED).list()) {
            if ((it.getFlag() & ItemConstants.COLD) == ItemConstants.COLD
                    && ((returnMapid == MapId.EL_NATH && thisMapid != MapId.ORBIS_TOWER_BOTTOM)
                    || returnMapid == MapId.INTERNET_CAFE)) {
                return true;        //protection from cold
            }
        }

        return false;
    }

    /** 换图内部实现：切图/进图/通知/事件 */
    private void changeMapInternal(final MapleMap to, final Point pos, Packet warpPacket) {
        if (!canWarpMap) {
            return;
        }
        if (getMap(to.getId(), true) == null) return; //判断地图不存在则直接返回并发送提示消息。

        this.mapTransitioning.set(true);
        // 显式清空“传送距离校验上下文”，避免跨图后旧上下文残留
        owner.clearTeleportDistanceContext();

        owner.unregisterChairBuff();
        clearBanishPlayerData();
        Trade.cancelTrade(owner, Trade.TradeResult.UNSUCCESSFUL_ANOTHER_MAP);
        owner.closePlayerInteractions();

        Party e = null;
        if (owner.getParty() != null && owner.getParty().getEnemy() != null) {
            e = owner.getParty().getEnemy();
        }
        final Party k = e;

        owner.sendPacket(warpPacket);
        map.removePlayer(owner);
        if (owner.getClient().getChannelServer().getPlayerStorage().getCharacterById(owner.getId()) != null) {
            map = to;
            owner.setPosition(pos);
            map.addPlayer(owner);
            visitMap(map);

            try (var ignored = Locks.acquire(owner.party.lock)) {
                if (owner.party.party != null) {
                    owner.party.mpc.setMapId(to.getId());
                    owner.sendPacket(PacketCreator.updateParty(owner.getClient().getChannel(), owner.party.party, PartyOperation.SILENT_UPDATE, null));
                    owner.party.updatePartyMemberHPInternal();
                }
            }
            if (owner.getParty() != null) {
                owner.getParty().setEnemy(k);
            }
            owner.party.silentPartyUpdateInternal(owner.getParty());  // EIM script calls inside
        } else {    //切换地图时卡住了
            log.warn(I18nUtil.getLogMessage("Character.Map.Change.warn2"), owner.getName(), map.getMapName(), map.getId());
            owner.getClient().disconnect(true, false);     // thanks BHB for noticing a player storage stuck case here
            return;
        }

        owner.marriage.notifyMapTransferToPartner(map.getId());

        //alas, new map has been specified when a warping was being processed...
        if (newWarpMap != -1) {
            canWarpMap = true;

            int temp = newWarpMap;
            newWarpMap = -1;
            changeMap(temp);
        } else {
            // if this event map has a gate already opened, render it
            EventInstanceManager eim = owner.getEventInstance();
            if (eim != null) {
                eim.recoverOpenedGate(owner, map.getId());
            }

            // if this map has obstacle components moving, make it do so for this client
            owner.sendPacket(PacketCreator.environmentMoveList(map.getEnvironment().entrySet()));
        }
    }

    private void eventChangedMap(int map) {
        EventInstanceManager eim = owner.getEventInstance();
        if (eim != null) {
            eim.changedMap(owner, map);
        }
    }

    private void eventAfterChangedMap(int map) {
        EventInstanceManager eim = owner.getEventInstance();
        if (eim != null) {
            eim.afterChangedMap(owner, map);
        }
    }

    // ── 地图历史 ──

    private Integer getVisitedMapIndex(MapleMap map) {
        int idx = 0;
        for (WeakReference<MapleMap> mapRef : lastVisitedMaps) {
            if (map.equals(mapRef.get())) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    void visitMap(MapleMap map) {
        mapHistoryLock.lock();
        try {
            int idx = getVisitedMapIndex(map);

            if (idx == -1) {
                if (lastVisitedMaps.size() == GameConfig.getServerInt("map_visited_size")) {
                    lastVisitedMaps.removeFirst();
                }
            } else {
                WeakReference<MapleMap> mapRef = lastVisitedMaps.remove(idx);
                lastVisitedMaps.add(mapRef);
                return;
            }

            lastVisitedMaps.add(new WeakReference<>(map));
        } finally {
            mapHistoryLock.unlock();
        }
    }

    List<Integer> getLastVisitedMapIds() {
        List<Integer> lastVisited = new ArrayList<>(5);

        mapHistoryLock.lock();
        try {
            for (WeakReference<MapleMap> lv : lastVisitedMaps) {
                MapleMap lvm = lv.get();

                if (lvm != null) {
                    lastVisited.add(lvm.getId());
                }
            }
        } finally {
            mapHistoryLock.unlock();
        }

        return lastVisited;
    }

    /** 地图历史快照（partyOperationUpdate 用；返回弱引用副本，调用方持锁读） */
    List<WeakReference<MapleMap>> getLastVisitedMaps() {
        mapHistoryLock.lock();
        try {
            return new LinkedList<>(lastVisitedMaps);
        } finally {
            mapHistoryLock.unlock();
        }
    }

    // ── 放逐（banish）──

    /** 放逐前位置是否仍可恢复（5 分钟内） */
    boolean canRecoverLastBanish() {
        return System.currentTimeMillis() - this.banishTime < MINUTES.toMillis(5);
    }

    /** 放逐前位置（地图 + 出生点） */
    Pair<Integer, Integer> getLastBanishData() {
        return new Pair<>(this.banishMap, this.banishSp);
    }

    /** 清空放逐前位置记录（换图/断线时调用） */
    void clearBanishPlayerData() {
        this.banishMap = -1;
        this.banishSp = -1;
        this.banishTime = 0;
    }

    /** 记录放逐前位置（回城卷轴/怪物放逐写入） */
    void setBanishPlayerData(int banishMap, int banishSp, long banishTime) {
        this.banishMap = banishMap;
        this.banishSp = banishSp;
        this.banishTime = banishTime;
    }

    /**
     * 怪物放逐：记录当前位置后强制换图到放逐点（可被防放逐卷轴返回）。
     * 穿着钉子鞋（SPIKES）且开启 use_spikes_avoid_banish 时免疫。
     */
    void changeMapBanish(int mapid, String portal, String msg) {
        if (GameConfig.getServerBoolean("use_spikes_avoid_banish")) {
            for (ItemSlot it : owner.getInventory(InventoryType.EQUIPPED).list()) {
                if ((it.getFlag() & ItemConstants.SPIKES) == ItemConstants.SPIKES) {
                    return;
                }
            }
        }

        int banMap = getMapId();
        int banSp = getMap().findClosestPlayerSpawnpoint(owner.getPosition()).getId();
        long banTime = System.currentTimeMillis();

        if (msg != null) {
            owner.dropMessage(5, msg);
        }

        MapleMap map_ = getWarpMap(mapid);
        Portal portal_ = map_.getPortal(portal);
        changeMap(map_, portal_ != null ? portal_ : map_.getRandomPlayerSpawnpoint());

        setBanishPlayerData(banMap, banSp, banTime);
    }
}
