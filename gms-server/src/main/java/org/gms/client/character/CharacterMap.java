package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.MapId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.packet.Packet;
import org.gms.net.server.Server;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.client.pet.Pet;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
import org.gms.server.Trade;
import org.gms.infra.Strand;
import org.gms.server.maps.Dragon;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.MapleMapRef;
import org.gms.server.maps.MapleMapStatic;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MiniDungeon;
import org.gms.server.maps.MiniDungeonInfo;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.Summon;
import org.gms.remote.modules.map.client.MoveLife;
import org.gms.remote.modules.map.client.movement.AbsoluteMove;
import org.gms.remote.modules.map.client.movement.ChangeEquipMove;
import org.gms.remote.modules.map.client.movement.ChairMove;
import org.gms.remote.modules.map.client.movement.JumpDownMove;
import org.gms.remote.modules.map.client.movement.LegacyMove3;
import org.gms.remote.modules.map.client.movement.LegacyMove9;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.remote.modules.map.client.movement.RelativeMove;
import org.gms.remote.modules.map.client.movement.TeleportMove;
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
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MINUTES;
import org.gms.client.inventory.EquipFlag;

/**
 * 地图模块组件：当前地图（map/mapId）+ 换图状态 + 换图流程（changeMap 系列）+ 地图历史 + 放逐（banish）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getMap/getMapId/changeMap/... 对外转发）。
 *
 * 边界：只承载地图管理语义——当前图状态、换图（warp）、地图历史、放逐（banish 城镇卷轴/怪物放逐）。
 * 换图流程中编排的其他模块（party/Trade/chair/event/summons 等）经 owner 门面调用；
 * partyOperationUpdate（组队地图协作）属 party 语义，留在 Character。
 * 地图访问经 {@link MapleMapRef}（player strand 侧句柄，doc/13）——本组件不持 MapleMap 类型；
 * warp 包构造（legacy PacketCreator 需 map 本体）以 unwrap 内联过渡。
 */
class CharacterMap implements org.gms.remote.modules.map.client.MapModule.Handler {
    private static final Logger log = LoggerFactory.getLogger(CharacterMap.class);

    private final Character owner;

    /** 收包 Handler 接插（角色入场绑定时由 Character 聚合调用，on strand，doc/12）。 */
    void bindClientHandlers(org.gms.remote.ClientEventHandlerRegistry registry) {
        registry.registerMap(this);
    }

    /**
     * MOVE_PLAYER 语义入口（player strand 上执行，doc/13 §12）：应用元素序列（chr 写）→
     * 编码中继成品 → map actor 异步（可见性差集/广播/回程 apply）。
     */
    @Override
    public void movePlayer(List<MoveElement> elements) {
        applyMovement(elements);

        final Point newPos = owner.getPosition();
        final Packet relay = owner.getRemote().map().movePlayer(owner.getId(), elements);
        final boolean gmOnly = owner.isHidden();
        final List<MapObject> visible = List.of(owner.getVisibleMapObjects());
        final Strand strand = owner.strand();
        if (strand == null) {
            return;   // 无会话 strand（理论不可达：本入口在 strand 上执行）
        }
        final MapleMapRef map = this.map;
        map.post("move", () -> map.onMove(new org.gms.server.maps.MapleMap.MoveMsg(strand, owner.ref(), owner.getClient(), newPos, relay, gmOnly, visible)));
    }

    /**
     * MOVE_LIFE 语义入口（player strand 上执行，doc/13 §18）：player 侧仅 guard——
     * mob 状态归地图域，语义快照（gms083 纯解码产出）post map actor 串行应用
     * （controller 校验/aggro/位置/ack/中继/可见性）。
     */
    @Override
    public void moveLife(MoveLife life) {
        if (owner.isChangingMaps()) {
            return;
        }
        final MapleMapRef map = this.map;
        final org.gms.remote.RemoteClient remote = owner.remote();   // 语义层引用快照过界（map 任务体零导航）
        map.post("move-life", () -> map.onMoveLife(new org.gms.server.maps.MapleMap.MoveLifeMsg(owner.ref(), owner.getClient(), remote, life)));
    }

    /**
     * 元素应用（chr 写：位置/姿态/反作弊上下文）——自 AbstractMovementPacketHandler.updatePosition
     * 的 player 分支迁移（character 域；相对移动按 delta 估算绝对落点，瞬移记录双坐标供攻击距离校验）。
     */
    private void applyMovement(List<MoveElement> elements) {
        for (MoveElement e : elements) {
            switch (e) {
                case AbsoluteMove m -> {
                    Point before = snapshotPosition();
                    Point after = new Point(m.x(), m.y());
                    owner.setPosition(after);
                    owner.setStance(m.stance());
                    owner.markRegularMove(before, after);
                }
                case RelativeMove m -> {
                    Point before = snapshotPosition();
                    Point after = estimateRelativeMovePosition(before, m.x(), m.y());
                    if (after != null) {
                        owner.setPosition(after);
                    }
                    owner.setStance(m.stance());
                    owner.markRegularMove(before, after);
                }
                case TeleportMove t -> {
                    Point before = snapshotPosition();
                    Point after = new Point(t.x(), t.y());
                    owner.setPosition(after);
                    owner.setStance(t.stance());
                    if (t.command() == 3 || t.command() == 4) {
                        // 瞬移前后坐标记录，供攻击距离双坐标校验使用
                        owner.markTeleportLikeMove(before, after);
                    }
                }
                case ChairMove c -> owner.setStance(c.stance());
                case JumpDownMove j -> {
                    Point before = snapshotPosition();
                    Point after = new Point(j.x(), j.y());
                    owner.setPosition(after);
                    owner.setStance(j.stance());
                    owner.markRegularMove(before, after);
                }
                case ChangeEquipMove c -> {
                }
                case LegacyMove9 l -> {
                }
                case LegacyMove3 l -> {
                }
            }
        }
    }

    private Point snapshotPosition() {
        Point currentPos = owner.getPosition();
        return currentPos != null ? new Point(currentPos) : null;
    }

    private Point estimateRelativeMovePosition(Point beforePos, int deltaX, int deltaY) {
        if (beforePos == null) {
            return null;
        }
        return new Point(beforePos.x + deltaX, beforePos.y + deltaY);
    }

    /** 当前地图句柄（player strand 侧） */
    MapleMapRef map;

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

    /** 最近访问地图历史（换图/回城用；ref 规范身份 = map 身份） */
    private final LinkedList<WeakReference<MapleMapRef>> lastVisitedMaps = new LinkedList<>();
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

    MapleMapRef getMap() {
        return map;
    }

    int getMapId() {
        if (map != null) {
            return map.getId();
        }
        return mapId;
    }

    void setMap(MapleMapRef map) {
        this.map = map;
    }

    void setMap(int PmapId) {
        this.mapId = PmapId;
    }

    void setMapId(int mapId) {
        this.mapId = mapId;
    }

    /**
     * 获取地图句柄
     * @param mapid 地图ID
     * @param showMsg   true = 地图不存在弹出提示，false = 不提示
     */
    MapleMapRef getMap(int mapid, boolean showMsg) {
        MapleMapRef map = MapleMapRef.of(owner.getClient().getChannelServer().getMapFactory(), mapid);
        if (map == null && showMsg) {
            String msg = I18nUtil.getMessage("Character.Map.Change.message1", Integer.toString(mapid));
            owner.dropMessage(6, msg);
        }
        return map;
    }

    /** 取地图句柄：事件图优先，其次怪物嘉年华图，最后普通地图工厂 */
    MapleMapRef getWarpMap(int map) {
        MapleMapRef warpMap;
        EventInstanceManager eim = owner.getEventInstance();
        if (eim != null) {
            warpMap = MapleMapRef.of(eim, map);
        } else if (owner.getMonsterCarnival() != null && owner.getMonsterCarnival().getEventMap().getId() == map) {
            warpMap = MapleMapRef.of(owner.getMonsterCarnival());
        } else {
            warpMap = MapleMapRef.of(owner.getClient().getChannelServer().getMapFactory(), map);
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
        MapleMapRef warpMap;
        EventInstanceManager eim = owner.getEventInstance();

        if (eim != null) {
            warpMap = MapleMapRef.of(eim, map);
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

    void changeMap(MapleMapRef to) {
        changeMap(to, 0);
    }

    void changeMap(MapleMapRef to, int portal) {
        changeMap(to, to.getPortal(portal));
    }

    void changeMap(final MapleMapRef target, Portal pto) {
        canWarpCounter++;

        eventChangedMap(target.getId());    // player can be dropped from an event here, hence the new warping target.  //玩家可以从这里的事件中退出，因此成为新的扭曲目标。
        MapleMapRef to = getWarpMap(target.getId());
        if (pto == null) {
            pto = to.getPortal(0);
        }
        // warp 包构造需 map 本体（legacy PacketCreator）——unwrap 内联过渡（组件不 import MapleMap）
        changeMapInternal(to, pto.getPosition(), PacketCreator.getWarpToMap(to.unwrap(), pto.getId(), owner));
        canWarpMap = false;

        canWarpCounter--;
        if (canWarpCounter == 0) {
            canWarpMap = true;
        }

        eventAfterChangedMap(getMapId());
    }

    void changeMap(final MapleMapRef target, final Point pos) {
        canWarpCounter++;

        eventChangedMap(target.getId());
        MapleMapRef to = getWarpMap(target.getId());
        changeMapInternal(to, pos, PacketCreator.getWarpToMap(to.unwrap(), 0x80, pos, owner));
        canWarpMap = false;

        canWarpCounter--;
        if (canWarpCounter == 0) {
            canWarpMap = true;
        }

        eventAfterChangedMap(getMapId());
    }

    void forceChangeMap(final MapleMapRef target, Portal pto) {
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
            //感谢Thora发现玩家实际上没有被扭曲到目标事件地图中（而是被发送到事件开始地图）
            mapEim.registerPlayer(owner, false);
        }

        if (pto == null) {
            pto = target.getPortal(0);
        }
        changeMapInternal(target, pto.getPosition(), PacketCreator.getWarpToMap(target.unwrap(), pto.getId(), owner));
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
        int returnMapid = MapleMapRef.of(owner.getClient().getChannelServer().getMapFactory(), thisMapid).getReturnMapId();

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
            if (it.getItem().getEquipInfo() != null && it.getItem().getEquipInfo().hasFlag(EquipFlag.COLD)
                    && ((returnMapid == MapId.EL_NATH && thisMapid != MapId.ORBIS_TOWER_BOTTOM)
                    || returnMapid == MapId.INTERNET_CAFE)) {
                return true;        //protection from cold
            }
        }

        return false;
    }

    /** 换图内部实现：切图/进图/通知/事件 */
    private void changeMapInternal(final MapleMapRef to, final Point pos, Packet warpPacket) {
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
        // 局部捕获旧图：lambda 读字段是执行时取值，下方 map = to 重赋值后会串图
        final MapleMapRef from = map;
        // removePlayer 缝合点（doc/13 §4）：审计过无脚本入口/无 pet 阻塞回询/无自发包；
        // 同步完成以保证同图传送时 remove 先于 add 的 destroy→spawn 包序（幽灵玩家防线）
        from.runIn("map-removePlayer", () -> from.removePlayer(owner.ref()));
        if (owner.getClient().getChannelServer().getPlayerStorage().getCharacterById(owner.getId()) != null) {
            map = to;
            owner.setPosition(pos);
            // 宠物召唤快照：本 strand 上采集后随边界传入（doc/13 §5.2；map shim 后 map 任务体
            // 内禁止 actor 回询 = 环死锁）
            final List<Pet> pets = owner.getPets().getSummonedPets();
            final boolean firstEnter = registerPlayer(owner, pets);   // shim run：登记段缝合点（party 快照在门面采集）
            enterMap(firstEnter, pets);                               // player strand：进图编舞（原 finishEnter 迁出）
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

    // ── 进图/拾取/移动（shim 缝合点语义见各方法注释）──

    /** 进图登记（shim supply 缝合点，doc/13 §5.2）——party 快照（player strand 采集）随边界传入 */
    boolean registerPlayer(Character chr, List<Pet> summonedPets) {
        return map.registerPlayer(chr.ref(), summonedPets, chr.getParty());
    }

    /**
     * 进图编舞（原 MapleMap.finishEnter verbatim 迁出——player strand 原位，§5.4 收口）：
     * 本体触点全部 owner 直调（不经 ref，canary 不触发）；静态事实经 {@code map.statics()}
     * 无锁直读；map 操作经 MapleMapRef 原位 helper（快照入参，体内零 CharacterRef 方法调用）。
     * 语句相对顺序与迁移前一致（发包字节序不变）。进图脚本按裁定注释留 FIXME（测试场景无 map script）。
     */
    void enterMap(boolean firstEnter, List<Pet> summonedPets) {
        final Character chr = owner;
        final MapleMapStatic st = map.statics();
        final World wserv = Server.getInstance().getWorld(st.world());
        chr.setMapId(st.mapid());
        chr.updateActiveEffects();

        if (st.decHP() > 0) {
            wserv.addPlayerHpDecrease(chr);
        } else {
            wserv.removePlayerHpDecrease(chr);
        }

        // FIXME(脚本桥): 进图脚本暂不执行（现测试场景无 map script）。恢复时脚本执行归 player 域、
        //  触发归 map 域（反向 post 通道），不得以 player strand 直调 ref 参数化 API 的旧形态回归。
        // MapScriptManager msm = MapScriptManager.getInstance();
        // if (firstEnter) {
        //     if (st.onFirstUserEnter().length() != 0) {
        //         msm.runMapScript(chr, "onFirstUserEnter/" + st.onFirstUserEnter(), true);
        //     }
        // }
        if (st.onUserEnter().length() != 0) {
            if (st.onUserEnter().equals("cygnusTest") && !MapId.isCygnusIntro(st.mapid())) {
                chr.saveLocation("INTRO");
            }
            // msm.runMapScript(chr, "onUserEnter/" + st.onUserEnter(), false);
        }
        if (FieldLimit.CANNOTUSEMOUNTS.check(st.fieldLimit()) && chr.getBuffedValue(EffectType.MONSTER_RIDING) != null) {
            chr.cancelEffectFromBuffStat(EffectType.MONSTER_RIDING);
            chr.cancelBuffStats(EffectType.MONSTER_RIDING);
        }

        if (st.mapid() == MapId.FROM_LITH_TO_RIEN) { // To Rien
            int travelTime = wserv.getTransportationTime((int) MINUTES.toMillis(1));
            chr.sendPacket(PacketCreator.getClock(travelTime / 1000));
            TimerManager.getInstance().schedule(() -> {
                if (chr.getMapId() == MapId.FROM_LITH_TO_RIEN) {
                    chr.changeMap(MapId.DANGEROUS_FOREST, 0);
                }
            }, travelTime);
        } else if (st.mapid() == MapId.FROM_RIEN_TO_LITH) { // To Lith Harbor
            int travelTime = wserv.getTransportationTime((int) MINUTES.toMillis(1));
            chr.sendPacket(PacketCreator.getClock(travelTime / 1000));
            TimerManager.getInstance().schedule(() -> {
                if (chr.getMapId() == MapId.FROM_RIEN_TO_LITH) {
                    chr.changeMap(MapId.LITH_HARBOUR, 3);
                }
            }, travelTime);
        } else if (st.mapid() == MapId.FROM_ELLINIA_TO_EREVE) { // To Ereve (SkyFerry)
            int travelTime = wserv.getTransportationTime((int) MINUTES.toMillis(2));
            chr.sendPacket(PacketCreator.getClock(travelTime / 1000));
            TimerManager.getInstance().schedule(() -> {
                if (chr.getMapId() == MapId.FROM_ELLINIA_TO_EREVE) {
                    chr.changeMap(MapId.SKY_FERRY, 0);
                }
            }, travelTime);
        } else if (st.mapid() == MapId.FROM_EREVE_TO_ELLINIA) { // To Victoria Island (SkyFerry)
            int travelTime = wserv.getTransportationTime((int) MINUTES.toMillis(2));
            chr.sendPacket(PacketCreator.getClock(travelTime / 1000));
            TimerManager.getInstance().schedule(() -> {
                if (chr.getMapId() == MapId.FROM_EREVE_TO_ELLINIA) {
                    chr.changeMap(MapId.ELLINIA_SKY_FERRY, 0);
                }
            }, travelTime);
        } else if (st.mapid() == MapId.FROM_EREVE_TO_ORBIS) { // To Orbis (SkyFerry)
            int travelTime = wserv.getTransportationTime((int) MINUTES.toMillis(8));
            chr.sendPacket(PacketCreator.getClock(travelTime / 1000));
            TimerManager.getInstance().schedule(() -> {
                if (chr.getMapId() == MapId.FROM_EREVE_TO_ORBIS) {
                    chr.changeMap(MapId.ORBIS_STATION, 0);
                }
            }, travelTime);
        } else if (st.mapid() == MapId.FROM_ORBIS_TO_EREVE) { // To Ereve From Orbis (SkyFerry)
            int travelTime = wserv.getTransportationTime((int) MINUTES.toMillis(8));
            chr.sendPacket(PacketCreator.getClock(travelTime / 1000));
            TimerManager.getInstance().schedule(() -> {
                if (chr.getMapId() == MapId.FROM_ORBIS_TO_EREVE) {
                    chr.changeMap(MapId.SKY_FERRY, 0);
                }
            }, travelTime);
        } else if (MiniDungeonInfo.isDungeonMap(st.mapid())) {
            MiniDungeon mmd = chr.getClient().getChannelServer().getMiniDungeon(st.mapid());
            if (mmd != null) {
                mmd.registerPlayer(chr);
            }
        } else if (GameConstants.isAriantColiseumArena(st.mapid())) {
            int pqTimer = (int) MINUTES.toMillis(10);
            chr.sendPacket(PacketCreator.getClock(pqTimer / 1000));
        }

        for (Pet pet : summonedPets) {
            // 原 MapleMap.getGroundBelow：calcPointBelow(pos.y-14) 后 y--（静态落点，footholds 直算）
            Point pos = MapleMapStatic.calcPointBelow(st.footholds(), new Point(chr.getPosition().x, chr.getPosition().y - 14));
            pos.y--;
            int fh = st.footholds().findBelow(pos).getId();
            pet.announceSummon(pos, fh);
        }

        chr.getRemote().pet().updateIgnoreList(chr);  // thanks OishiiKawaiiDesu for noticing pet item ignore registry erasing upon changing maps

        if (chr.getMonsterCarnival() != null) {
            chr.sendPacket(PacketCreator.getClock(chr.getMonsterCarnival().getTimeLeftSeconds()));
            if (MapleMapStatic.isCPQMapId(st.mapid())) {
                int team = -1;
                int oposition = -1;
                if (chr.getTeam() == 0) {
                    team = 0;
                    oposition = 1;
                }
                if (chr.getTeam() == 1) {
                    team = 1;
                    oposition = 0;
                }
                chr.sendPacket(PacketCreator.startMonsterCarnival(chr, team, oposition));
            }
        }

        chr.removeSandboxItems();

        if (chr.getChalkboard() != null) {
            if (!GameConstants.isFreeMarketRoom(st.mapid())) {
                chr.sendPacket(PacketCreator.useChalkboard(chr, false)); // update player's chalkboard when changing maps found thanks to Vcoc
            } else {
                chr.setChalkboard(null);
            }
        }

        // （原 GM 隐身特效包分支：isHidden 按"单机无 GM"裁定删除，恒 false）

        List<MapObject> addRefs = map.sendObjectPlacement(chr.getClient(), chr.getPosition(), chr.getId(), chr.getSummonsValues());
        chr.applyVisibleMapObjects(addRefs, List.of());

        map.closeEventJoinPortal();
        if (st.fieldType() == 81 || st.fieldType() == 82) {   // 原 hasForcedEquip（fieldType 静态判定内联）
            chr.sendPacket(PacketCreator.showForcedEquip(-1));
        }
        if (st.fieldType() == 4 || st.fieldType() == 19) {    // 原 specialEquip
            chr.sendPacket(PacketCreator.coconutScore(0, 0));
            chr.sendPacket(PacketCreator.showForcedEquip(chr.getTeam()));
        }
        map.registerEnterObjects(chr.ref(), chr.getObjectId(), chr.getPlayerShop());

        final Dragon dragon = chr.getDragon();
        if (dragon != null) {
            map.spawnDragon(dragon, chr.getPosition(), chr.ref());
        }

        BuffEffectData summonStat = chr.getStatForBuff(EffectType.SUMMON);
        if (summonStat != null) {
            Summon summon = chr.getSummonByKey(summonStat.getSourceId());
            summon.setPosition(chr.getPosition());
            map.spawnSummonExcludeOwner(summon, chr.ref());
            // owner 份（原 ranged 广播内含 owner：可见集登记 + 与 packetbakery 同形的 spawn 包）
            chr.addVisibleMapObject(summon);
            chr.sendPacket(PacketCreator.spawnSummon(summon, true));
        }
        map.sendMapEffectData(chr.getClient());
        chr.sendPacket(PacketCreator.resetForcedStats());
        if (MapId.isGodlyStatMap(st.mapid())) {
            chr.sendPacket(PacketCreator.aranGodlyStats());
        }
        if (chr.getEventInstance() != null && chr.getEventInstance().isTimerStarted()) {
            chr.sendPacket(PacketCreator.getClock((int) (chr.getEventInstance().getTimeLeft() / 1000)));
        }
        if (chr.getFitness() != null && chr.getFitness().isTimerStarted()) {
            chr.sendPacket(PacketCreator.getClock((int) (chr.getFitness().getTimeLeft() / 1000)));
        }

        if (chr.getOla() != null && chr.getOla().isTimerStarted()) {
            chr.sendPacket(PacketCreator.getClock((int) (chr.getOla().getTimeLeft() / 1000)));
        }

        if (st.mapid() == MapId.EVENT_SNOWBALL) {
            chr.sendPacket(PacketCreator.rollSnowBall(true, 0, null, null));
        }

        if (st.clock()) {
            Calendar cal = Calendar.getInstance();
            chr.sendPacket(PacketCreator.getClockTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)));
        }
        if (st.boat()) {   // 原 hasBoat()：boat 能力静态，docked 运行时态经 supply 自读
            chr.sendPacket(PacketCreator.boatPacket(map.isBoatDocked()));
        }

        chr.receivePartyMemberHP();
        Server.getInstance().registerAnnouncePlayerDiseases(chr.getClient());
    }

    // ── 地图历史 ──

    private Integer getVisitedMapIndex(MapleMapRef map) {
        int idx = 0;
        for (WeakReference<MapleMapRef> mapRef : lastVisitedMaps) {
            if (map.equals(mapRef.get())) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    void visitMap(MapleMapRef map) {
        mapHistoryLock.lock();
        try {
            int idx = getVisitedMapIndex(map);

            if (idx == -1) {
                if (lastVisitedMaps.size() == GameConfig.getServerInt("map_visited_size")) {
                    lastVisitedMaps.removeFirst();
                }
            } else {
                WeakReference<MapleMapRef> mapRef = lastVisitedMaps.remove(idx);
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
            for (WeakReference<MapleMapRef> lv : lastVisitedMaps) {
                MapleMapRef lvm = lv.get();

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
    List<WeakReference<MapleMapRef>> getLastVisitedMaps() {
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
                if (it.getItem().getEquipInfo() != null && it.getItem().getEquipInfo().hasFlag(EquipFlag.SPIKES)) {
                    return;
                }
            }
        }

        int banMap = getMapId();
        int banSp = map.findClosestPlayerSpawnpoint(owner.getPosition()).getId();
        long banTime = System.currentTimeMillis();

        if (msg != null) {
            owner.dropMessage(5, msg);
        }

        MapleMapRef map_ = getWarpMap(mapid);
        Portal portal_ = map_.getPortal(portal);
        changeMap(map_, portal_ != null ? portal_ : map_.getRandomPlayerSpawnpoint());

        setBanishPlayerData(banMap, banSp, banTime);
    }
}
