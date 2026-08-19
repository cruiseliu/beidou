package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.EffectType;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.MapId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.packet.Packet;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyOperation;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.Trade;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.util.I18nUtil;
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

/**
 * 地图模块组件：当前地图（map/mapId）+ 换图状态 + 换图流程（changeMap 系列）+ 地图历史。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getMap/getMapId/changeMap/... 对外转发）。
 *
 * 边界：只承载地图管理语义——当前图状态、换图（warp）、地图历史。
 * 换图流程中编排的其他模块（party/Trade/chair/banish/event/summons 等）经 owner 门面调用；
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

    /** 按 id 取地图（带错误提示）；null 且 showMsg 时告警 */
    MapleMap getMap(int mapid, boolean showMsg) {
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

    boolean isChangingMaps() {
        return this.mapTransitioning.get();
    }

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
        owner.clearBanishPlayerData();
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

            owner.prtLock.lock();
            try {
                if (owner.party != null) {
                    owner.mpc.setMapId(to.getId());
                    owner.sendPacket(PacketCreator.updateParty(owner.getClient().getChannel(), owner.party, PartyOperation.SILENT_UPDATE, null));
                    owner.updatePartyMemberHPInternal();
                }
            } finally {
                owner.prtLock.unlock();
            }
            if (owner.getParty() != null) {
                owner.getParty().setEnemy(k);
            }
            owner.silentPartyUpdateInternal(owner.getParty());  // EIM script calls inside
        } else {    //切换地图时卡住了
            log.warn(I18nUtil.getLogMessage("Character.Map.Change.warn2"), owner.getName(), map.getMapName(), map.getId());
            owner.getClient().disconnect(true, false);     // thanks BHB for noticing a player storage stuck case here
            return;
        }

        owner.notifyMapTransferToPartner(map.getId());

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
}
