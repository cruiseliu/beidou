package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MapId;
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
import org.gms.scripting.JsModule;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.MapObjectType;
import org.gms.util.AssertUtil;
import org.gms.server.maps.MapleMapRef;
import org.gms.server.maps.MapleMapStatic;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MiniDungeon;
import org.gms.server.maps.MiniDungeonInfo;
import org.gms.server.maps.Summon;
import org.gms.server.maps.MapleMap;
import org.gms.remote.RemoteClient;
import org.gms.remote.modules.map.MapModule;
import org.gms.remote.modules.map.client.ChangeMapEvent;
import org.gms.remote.modules.map.client.MoveLife;
import org.gms.remote.modules.map.client.ReviveHereEvent;
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
import org.gms.server.maps.PortalGateSnap;
import org.gms.server.maps.PortalStatic;
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
class CharacterMap implements MapModule.Handler {
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
        final List<Integer> visibleOids = owner.getVisibleMapObjectOids();   // 可见视图 oid 快照（值化，原活引用列表）
        final Strand strand = owner.strand();
        if (strand == null) {
            return;   // 无会话 strand（理论不可达：本入口在 strand 上执行）
        }
        final MapleMapRef map = this.map;
        map.broadcastCharacterMove(owner.getId(), elements);   // 他人流中继（map actor 逐连接语义投递）
        map.handleCharacterMove(owner.ref(), newPos, visibleOids); // 可见性差集（map actor）
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
        final RemoteClient remote = owner.remote();   // 语义层引用快照过界（map 任务体零导航）
        map.onMoveLife(new MapleMap.MoveLifeMsg(owner.ref(), owner.getClient(), remote, life));
    }

    /**
     * 切图完成确认语义入口（player strand 上执行）：切图标志复位 + homing beacon 重挂
     * → post map actor 做 mob 视图重建（revoke 控制 → destroy → respawn → 重挂 controller，
     * 修复客户端切图后的 mob 状态显示）。历史 isHidden 门（隐藏角色不控 mob）恒 false
     * （单机版没有 GM），分支不迁移。
     */
    @Override
    public void mapTransition() {
        mapTransitioning.set(false);

        // TODO: move to skill script
        owner.specialSkills.resetHomingBeaconOnChangeMap();

        map.onTransitionMobView(owner.ref());
    }

    /**
     * 脚本传送门入口（player strand 上执行）：门禁走 map actor 快照（portalGate）+
     * 门存在性/进入冷却/屏蔽名单/换图态/封禁五重校验（拒绝路径的回包解锁已收编至
     * EnterPortalTranslator.afterEmit），在途交易随换图取消；脚本门走 ESM 桥，
     * 非脚本门内建 warp（落点自静态半快照解析）。语义主体自 ChangeMapSpecialHandler
     * verbatim 迁移。
     */
    @Override
    public void enterPortal(String portalName) {
        PortalGateSnap gate = map.portalGate(portalName);
        PortalStatic portal = map.statics().portal(portalName);
        if (gate == null || owner.portalDelay() > Server.getInstance().getCurrentTime()
                || (gate.scriptName() != null && owner.getBlockedPortals().contains(gate.scriptName()))) {
            log.warn("走传送门拒绝(SPECIAL): 玩家 {} 地图 {} 传送门 {} 原因: {}", owner.getName(), owner.getMapId(), portalName,
                    gate == null ? "传送门不存在" : (owner.portalDelay() > Server.getInstance().getCurrentTime() ? "冷却中" : "被屏蔽"));
            return;
        }
        if (owner.isChangingMaps()) {
            return;
        }
        if (owner.getTrade() != null) {
            Trade.cancelTrade(owner, Trade.TradeResult.UNSUCCESSFUL_ANOTHER_MAP);
        }
        String scriptName = gate.scriptName();
        if (scriptName != null) {
            // FIXME(onlyOnce): WZ onlyOnce=1 的脚本门一次性触发是服务端职责，且需持久化
            //  （跨重登/重进图仍有效）——现状仅靠客户端自拦，服务端对重入无仲裁（会重复
            //  执行脚本/重发演出）。接入时按 script+mapid 走 Character.enteredScript 式
            //  持久化记账，命中即视为无脚本门后放行。
            // 脚本门：ESM 桥——actorscripts/map/<mapid>.js 按 WZ portal script 名同名导出，
            // 返回 true = 门已处理（演出/warp 由脚本语义决定）；无模块/无导出/脚本失败 =
            // 无脚本门（回包解锁由 EnterPortalTranslator.afterEmit 统一兜底）。
            runPortalScript(scriptName);
            return;
        }
        // 非脚本门：内建 warp（原 GenericPortal.enterPortal 分支上移；落点自静态半快照解析）
        if (!(owner.getChalkboard() != null && GameConstants.isFreeMarketRoom(portal.targetMapId()))) {
            MapleMapRef to = getWarpMap(portal.targetMapId());
            PortalStatic pto = to.statics().portal(portal.target());
            if (pto == null) {
                pto = to.statics().portal(0);
            }
            changeMap(to, pto);
        } else {
            owner.dropMessage(5, "You cannot enter this map with the chalkboard opened.");
        }
    }

    /** portal 脚本桥：脚本执行归 player 域（moduleFor/call 经 ScriptRunner 串行进 context）。 */
    private boolean runPortalScript(String scriptName) {
        JsModule module;
        try {
            module = owner.getScriptRunner().moduleFor("map/" + map.statics().mapid() + ".js");
        } catch (RuntimeException e) {
            return false;   // 模块缺失 = 无脚本门
        }
        if (module.get(scriptName) == null) {
            return false;   // 无同名导出 = 无脚本门
        }
        try {
            return Boolean.TRUE.equals(module.call(scriptName));
        } catch (RuntimeException e) {
            log.warn("portal 脚本执行失败: map={} script={}", map.statics().mapid(), scriptName, e);
            return false;   // 对齐旧 PortalScriptManager 吞异常 → enableActions 语义
        }
    }

    // ── CHANGE_MAP（走门/复活/显式 warp；原 ChangeMapHandler 语义主体 verbatim 迁移）──
    // 回包解锁已收编至 ChangeMapTranslator.afterEmit（gameplay 零散点）；暂不设 strict
    // 窗口：changeMap 链的 removePlayer 任务体仍触达入场者 ref（canary 未排，随其快照化
    // 批次翻转）。显式目标 warp 原有非 GM 白名单（六段剧情序列枚举）已删——单机环境放
    // 反作弊壳，客户端声明直接放行；其中 20100 case（goLith 空图开场，无入口）确认死
    // 分支一并下线。

    /**
     * 走传送门/白名单 warp 意图入口（CHANGE_MAP mode=0）。复活分支按服务端存活事实进入
     * （mode 字节只是客户端声明，legacy 即如此）。
     */
    @Override
    public void changeMap(ChangeMapEvent event) {
        if (!changeMapGuard()) {
            return;
        }
        PortalView view = portalView(event.portalName());
        applyDeclaredTarget(event.targetMapId(), false);
        portalTail(event.portalName(), view);
    }

    /**
     * 原地复活意图入口（CHANGE_MAP mode=1）。portalName 按 wire 空串参与尾段
     * （复活无门户概念，getPortal 必空，legacy 每次复活同样路径）。
     */
    @Override
    public void reviveHere(ReviveHereEvent event) {
        if (!changeMapGuard()) {
            return;
        }
        PortalView view = portalView("");
        applyDeclaredTarget(event.targetMapId(), event.wheel());
        portalTail("", view);
    }

    /** 门快照对（开闭动态位 + 静态事实），尾段校验的输入。 */
    private record PortalView(PortalGateSnap gate, PortalStatic portal) {
    }

    /** 门快照采集：动态位 shim supply（查无门 = null），静态事实豁免直读。 */
    private PortalView portalView(String portalName) {
        return new PortalView(map.portalGate(portalName), map.statics().portal(portalName));
    }

    /**
     * 入口守卫切片（CHANGE_MAP 三形态共用）：换图过渡态拒绝、在途交易取消、商城开启
     * 断连。返回 false = 已终结。
     */
    private boolean changeMapGuard() {
        if (owner.isChangingMaps()) {
            log.warn("走传送门拒绝(换图中): 玩家 {} 地图 {}", owner.getName(), owner.getMapId());
            return false;
        }
        if (owner.getTrade() != null) {
            Trade.cancelTrade(owner, Trade.TradeResult.UNSUCCESSFUL_ANOTHER_MAP);
        }
        if (owner.getCashShop().isOpened()) {   // 商城开着走门 = 非法态（legacy 断连）
            owner.getClient().disconnect(false, false);
            return false;
        }
        return true;
    }

    /** 显式目标图应用（-1 = 纯走门，无前置动作）：存活按声明 warp，死亡走复活路径。 */
    private void applyDeclaredTarget(int targetMapId, boolean wheel) {
        if (targetMapId == -1) {
            return;
        }
        if (!owner.isAlive()) {
            revive(wheel);
            return;
        }
        MapleMapRef to = getWarpMap(targetMapId);
        changeMap(to, to.statics().portal(0));
    }

    /** 死亡复活路径：转盘原地复活（持有校验在先）→ 事件脚本复活 → 回程图 respawn。 */
    private void revive(boolean wheel) {
        if (wheel && owner.haveItemWithId(ItemId.WHEEL_OF_FORTUNE, false)) {
            // thanks lucasziron (lziron) for showing revivePlayer() triggering by Wheel
            InventoryManipulator.removeById(owner.getClient(), InventoryType.CASH, ItemId.WHEEL_OF_FORTUNE, 1, true, false);
            owner.sendPacket(PacketCreator.showWheelsLeft(owner.getItemQuantity(ItemId.WHEEL_OF_FORTUNE, false)));
            owner.updateHp(50);
            changeMap(map, map.statics().findClosestPlayerSpawnpoint(owner.getPosition()));
            return;
        }
        boolean executeStandardPath = true;
        EventInstanceManager eim = owner.getEventInstance();
        if (eim != null) {
            executeStandardPath = eim.revivePlayer(owner);
        }
        if (executeStandardPath) {
            owner.respawn(map.getReturnMapId());
        }
    }

    /**
     * 门尾段：关门拒绝（blocked 回包）→ 活动计时重置 → 距离校验 → 走门（委托
     * {@link #enterPortal(String)}，CHANGE_MAP_SPECIAL 同款，含 ESM 脚本桥）/ 查无门告警。
     */
    private void portalTail(String portalName, PortalView view) {
        if (view.gate() != null && !view.gate().status()) {
            owner.sendPacket(PacketCreator.blockedMessage(1));
            return;
        }

        if (owner.getMapId() == MapId.FITNESS_EVENT_LAST) {
            owner.getFitness().resetTimes();
        } else if (owner.getMapId() == MapId.OLA_EVENT_LAST_1 || owner.getMapId() == MapId.OLA_EVENT_LAST_2) {
            owner.getOla().resetTimes();
        }

        if (view.portal() == null) {
            log.warn("走传送门拒绝: 玩家 {} 地图 {} 找不到传送门 {}", owner.getName(), owner.getMapId(), portalName);
            return;
        }
        if (view.portal().position().distanceSq(owner.getPosition()) > 400000) {
            log.warn("走传送门拒绝: 玩家 {} 地图 {} 传送门 {} 距离过远 (门=({},{}) 玩家=({},{}))",
                    owner.getName(), owner.getMapId(), portalName,
                    view.portal().position().x, view.portal().position().y, owner.getPosition().x, owner.getPosition().y);
            return;
        }
        enterPortal(portalName);
    }

    /**
     * 元素应用（chr 写：位置/姿态/反作弊上下文）——自 AbstractMovementPacketHandler.updatePosition
     * 的 player 分支迁移（character 域；相对移动按 delta 估算绝对落点，瞬移记录双坐标供攻击距离校验）。
     */
    private void applyMovement(List<MoveElement> elements) {
        for (MoveElement e : elements) {
            switch (e) {
                case AbsoluteMove m -> {
                    // Point before = snapshotPosition();
                    Point after = new Point(m.x(), m.y());
                    owner.setPosition(after);
                    owner.setStance(m.stance());
                    // owner.markRegularMove(before, after);
                }
                case RelativeMove m -> {
                    Point before = snapshotPosition();
                    Point after = estimateRelativeMovePosition(before, m.x(), m.y());
                    if (after != null) {
                        owner.setPosition(after);
                    }
                    owner.setStance(m.stance());
                    // owner.markRegularMove(before, after);
                }
                case TeleportMove t -> {
                    // Point before = snapshotPosition();
                    Point after = new Point(t.x(), t.y());
                    owner.setPosition(after);
                    owner.setStance(t.stance());
                    if (t.command() == 3 || t.command() == 4) {
                        // 瞬移前后坐标记录，供攻击距离双坐标校验使用
                        // owner.markTeleportLikeMove(before, after);
                    }
                }
                case ChairMove c -> owner.setStance(c.stance());
                case JumpDownMove j -> {
                    // Point before = snapshotPosition();
                    Point after = new Point(j.x(), j.y());
                    owner.setPosition(after);
                    owner.setStance(j.stance());
                    // owner.markRegularMove(before, after);
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

        PortalStatic portal = switch (pt) {
            case null -> warpMap.statics().randomPlayerSpawnpoint();
            case Integer i -> warpMap.statics().portal(i);
            case String s -> warpMap.statics().portal(s);
            case Portal p -> PortalStatic.of(p);
            default -> warpMap.statics().portal(0);
        };
        changeMap(warpMap, portal);
    }

    void changeMap(MapleMapRef to) {
        changeMap(to, 0);
    }

    void changeMap(MapleMapRef to, int portal) {
        changeMap(to, to.statics().portal(portal));
    }

    void changeMap(final MapleMapRef target, PortalStatic pto) {
        canWarpCounter++;

        eventChangedMap(target.getId());    // player can be dropped from an event here, hence the new warping target.  //玩家可以从这里的事件中退出，因此成为新的扭曲目标。
        MapleMapRef to = getWarpMap(target.getId());
        if (pto == null) {
            pto = to.statics().portal(0);
        }
        // warp 主包走语义层（ChangeMapServerEvent → SetFieldPacket.Warp）
        changeMapInternal(to, pto.position(), pto.id(), null);
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
        changeMapInternal(to, pos, -1, pos);
        canWarpMap = false;

        canWarpCounter--;
        if (canWarpCounter == 0) {
            canWarpMap = true;
        }

        eventAfterChangedMap(getMapId());
    }

    void forceChangeMap(final MapleMapRef target, PortalStatic pto) {
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
            pto = target.statics().portal(0);
        }
        changeMapInternal(target, pto.position(), pto.id(), null);
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
    /**
     * 换图主干：warp 主包经语义层（ChangeMapServerEvent → SetFieldPacket.Warp）在
     * 离图清理之前发出；spawnPosition = null 走传送门落点，非 null 走坐标落点（0x80
     * 形态标记归 route 侧版本词汇）。
     */
    private void changeMapInternal(final MapleMapRef to, final Point pos, final int spawnPoint, final Point spawnPosition) {
        if (!canWarpMap) {
            return;
        }
        if (getMap(to.getId(), true) == null) return; //判断地图不存在则直接返回并发送提示消息。

        this.mapTransitioning.set(true);
        // 显式清空“传送距离校验上下文”，避免跨图后旧上下文残留
        // owner.clearTeleportDistanceContext();

        owner.unregisterChairBuff();
        clearBanishPlayerData();
        Trade.cancelTrade(owner, Trade.TradeResult.UNSUCCESSFUL_ANOTHER_MAP);
        owner.closePlayerInteractions();

        Party e = null;
        if (owner.getParty() != null && owner.getParty().getEnemy() != null) {
            e = owner.getParty().getEnemy();
        }
        final Party k = e;

        if (spawnPosition != null) {
            owner.remote().map().changeMapServerAt(to.getId(), owner.getHp(), spawnPosition);
        } else {
            owner.remote().map().changeMapServer(to.getId(), spawnPoint, owner.getHp());
        }
        // 局部捕获旧图：lambda 读字段是执行时取值，下方 map = to 重赋值后会串图
        final MapleMapRef from = map;
        // 离图收尾切片（player strand，原 removePlayer 任务体 player 域段前置）：
        // controller 重分配 + MiniDungeon 退场 → map 域摘除（载荷键控，零 ref 触达）→ leaveMap。
        // 同步完成以保证同图传送时 remove 先于 add 的 destroy→spawn 包序（幽灵玩家防线）。
        owner.releaseControlledMonsters();
        owner.leaveMiniDungeon();
        from.removePlayer(owner.removeFacts());
        owner.leaveMap();
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

        // FIXME(脚本桥): 进图脚本暂不执行——围栏断言已按裁定关闭：遇脚本地图静默跳过
        //  （explorationPoint / onFirstUserEnter 等行为缺口待 map 脚本桥排期）。恢复时脚本
        //  执行归 player 域、触发归 map 域（反向 post 通道）。
        // MapScriptManager msm = MapScriptManager.getInstance();
        // if (firstEnter && st.onFirstUserEnter().length() != 0) {
        //     msm.runMapScript(chr, "onFirstUserEnter/" + st.onFirstUserEnter(), true);
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

        // 可见视图随进图重建：先清空（陈图条目随 reset 退役），placement 回来的值条目直接登记
        // （supply 同步返回 = seed 先于编舞后续，无窗口）
        owner.mapView().reset();
        final List<MapView.Entry> viewAdds = map.sendObjectPlacement(owner.ref(), chr.getPosition(), chr.getId(), chr.getSummonsValues());
        owner.mapView().addAll(viewAdds);

        map.closeEventJoinPortal();
        if (st.fieldType() == 81 || st.fieldType() == 82) {   // 原 hasForcedEquip（fieldType 静态判定内联）
            chr.sendPacket(PacketCreator.showForcedEquip(-1));
        }
        if (st.fieldType() == 4 || st.fieldType() == 19) {    // 原 specialEquip
            chr.sendPacket(PacketCreator.coconutScore(0, 0));
            chr.sendPacket(PacketCreator.showForcedEquip(chr.getTeam()));
        }
        map.registerEnterObjects(chr.ref(), chr.getObjectId(), chr.getPlayerShop());

        // dragon 功能当前版本不支持（MapleMap 侧投放/移除链已下线）：龙对象若经转职
        // 路径（createDragon）存在，换图在此响断言——tripwire，不静默半支持。
        // final Dragon dragon = chr.getDragon();
        // if (dragon != null) {
        //     map.spawnDragon(dragon, chr.getPosition(), chr.ref());
        // }
        AssertUtil.isTrue(chr.getDragon() == null, "dragon 不受支持却已存在 (cid=" + chr.getId() + ")");

        BuffEffectData summonStat = chr.getStatForBuff(EffectType.SUMMON);
        if (summonStat != null) {
            Summon summon = chr.getSummonByKey(summonStat.getSourceId());
            summon.setPosition(chr.getPosition());
            map.spawnSummonExcludeOwner(summon, chr.ref());
            // owner 份（原 ranged 广播内含 owner：可见集登记 + 与 packetbakery 同形的 spawn 包）
            chr.addVisibleMapObject(summon.getObjectId(), new MapView.MapObjectInfo(MapObjectType.SUMMON, 0));
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
        int banSp = map.statics().findClosestPlayerSpawnpoint(owner.getPosition()).id();
        long banTime = System.currentTimeMillis();

        if (msg != null) {
            owner.dropMessage(5, msg);
        }

        MapleMapRef map_ = getWarpMap(mapid);
        PortalStatic portal_ = map_.statics().portal(portal);
        changeMap(map_, portal_ != null ? portal_ : map_.statics().randomPlayerSpawnpoint());

        setBanishPlayerData(banMap, banSp, banTime);
    }
}
