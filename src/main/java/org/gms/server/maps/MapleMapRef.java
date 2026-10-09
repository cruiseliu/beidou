package org.gms.server.maps;

import org.gms.client.Client;
import org.gms.infra.StrictWindow;
import org.gms.infra.PipelineContext;
import org.gms.client.Player;
import org.gms.client.character.CharacterRef;
import org.gms.client.character.MapView;
import org.gms.client.pet.Pet;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.infra.ActorShim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.net.packet.Packet;
import org.gms.net.server.world.Party;
import org.gms.util.AssertUtil;

import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.Monster;
import org.gms.server.life.NPC;
import org.gms.server.partyquest.MonsterCarnival;

import java.awt.Point;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 地图的 player strand 侧句柄（doc/13）：Character 及其组件对地图的全部访问经此类型，
 * 持有 actor shim（同图所有角色共享同一 ref/shim——串行化边界在图，不在角色）。
 *
 * <p><b>规范身份</b>：每个 MapleMap 实例构造自己的唯一 ref（{@link #of(MapleMap)} 幂等），
 * ref 的 identity 即 map identity（跨角色同图比较/弱引用历史表依赖此性质）。
 *
 * <p><b>接口 = client.character 的用法并集</b>（封闭集，外部调用方经 {@link #unref()}）。
 * <b>调用语义（P4 翻转后）</b>：写方法经 {@code shim.run}、有返回值经 {@code shim.supply}
 * （阻塞至 map actor 完成——与 shim 化前的直接调用时序等价，但串行入队）；唯一豁免：
 * {@link #finishEnter} 是 player strand 原位方法（跑脚本，永不入 shim 任务体）。
 * 见 doc/13 迁移记录。
 */
public final class MapleMapRef {

    private static final Logger log = LoggerFactory.getLogger(MapleMapRef.class);

    final int mapId;
    final MapleMap map;
    final ActorShim shim;

    MapleMapRef(MapleMap map, String shimName) {
        this.mapId = map.getId();
        this.map = map;
        this.shim = ActorShim.create(shimName,
                new PipelineContext.Owner(PipelineContext.OwnerType.MAP, map.getId()));
    }

    // ── 规范化边界 ──

    /** 幂等：map 实例的唯一 ref（null 透传） */
    public static MapleMapRef of(MapleMap map) {
        return map != null ? map.ref : null;
    }

    /** 工厂查询（地图缺失返回 null——调用方负责提示语义） */
    public static MapleMapRef of(MapManager factory, int mapId) {
        return of(factory.getMap(mapId));
    }

    public static MapleMapRef of(EventInstanceManager eim, int mapId) {
        return of(eim.getMapInstance(mapId));
    }

    public static MapleMapRef of(MonsterCarnival carnival) {
        return of(carnival.getEventMap());
    }

    /** 还原 map 本体（legacy 外部门面用；阶段二外部迁 ref 后收口） */
    public MapleMap unref() {
        assertNotInStrictPipeline("unref");
        return map;
    }

    /**
     * 直调守卫（迁移 canary，与 {@link org.gms.client.character.CharacterRef} 同罪口径）：
     * 本组方法绕过 shim 直触 map 本体，strict 收包执行窗口内调用即抛 AssertionError，
     * 由 strand/shim fail-safe 记日志（定位用，不中断服务）。statics() 豁免（不可变读）。
     */
    private void assertNotInStrictPipeline(String what) {
        PipelineContext ctx = PipelineContext.current();
        if (ctx != null && ctx.kinds.contains(StrictWindow.STRAND)) {
            if (ctx.ownerType == PipelineContext.OwnerType.MAP && ctx.ownerId == mapId) {
                return;   // 本域自访
            }
            if (ctx.mode == StrictWindow.Mode.LOG) {
                log.error("strict 管线执行窗口内经 MapleMapRef 直调 map 本体: {} (map={}) [log 模式]", what, mapId, new RuntimeException("call site"));
                return;
            }
            throw new AssertionError("strict 管线执行窗口内经 MapleMapRef 直调 map 本体: " + what + " (map=" + mapId + ")");
        }
    }

    /**
     * 静态内容视图（WZ 装载期决定，装载后不可变）：与 map 本体共享同一实例，
     * player strand 可无锁直读、不走 actor api。只读纪律——运行时可变状态
     * （角色/掉落/事件等）不在此，仍走 shim 查询。
     */
    public MapleMapStatic statics() {
        return map.statics();
    }

    // ── shim 通道（既有异步语义，透传）──

    /** player→map 通知类调用：经 shim 异步执行（FIFO 派发序，doc/13 §1） */
    public void post(String task, Runnable r) {
        shim.post(task, r);
    }

    /** player→map 排序敏感缝合点：经 shim 同步完成 */
    public void runIn(String task, Runnable r) {
        shim.run(task, r);
    }

    // ── 查询（supply：阻塞至 map actor 完成）──

    public int getId() {
        return mapId;
    }

    /** 脚本门门禁快照（动态三元组入域时点抽取；查无门返回 null） */
    public PortalGateSnap portalGate(String portalName) {
        return shim.supply("portalGate", () -> map.portalGate(portalName));
    }

    /** 脚本门开启位（事件脚本/Monitor 写入） */
    public void setPortalStatus(String portalName, boolean open) {
        shim.run("setPortalStatus", () -> map.setPortalStatus(portalName, open));
    }

    /** GM 开关门 */
    public void setPortalState(String portalName, boolean state) {
        shim.run("setPortalState", () -> map.setPortalState(portalName, state));
    }

    /** 事件脚本阶段绑定门脚本名（EventInstanceManager） */
    public void setPortalScript(String portalName, String script) {
        shim.run("setPortalScript", () -> map.setPortalScript(portalName, script));
    }

    public int getReturnMapId() {
        return shim.supply("getReturnMapId", map::getReturnMapId);
    }

    public String getMapName() {
        return shim.supply("getMapName", map::getMapName);
    }

    public Map<String, Integer> getEnvironment() {
        return shim.supply("getEnvironment", map::getEnvironment);
    }

    public EventInstanceManager getEventInstance() {
        return shim.supply("getEventInstance", map::getEventInstance);
    }

    public FootholdTree getFootholds() {
        return shim.supply("getFootholds", map::getFootholds);
    }

    public int getHPDec() {
        return shim.supply("getHPDec", map::getHPDec);
    }

    public int getHPDecProtect() {
        return shim.supply("getHPDecProtect", map::getHPDecProtect);
    }

    public List<CharacterRef> getAllPlayers() {
        return shim.supply("getAllPlayers", map::getAllPlayers);
    }

    public List<MapObject> getMonsters() {
        return shim.supply("getMonsters", map::getMonsters);
    }

    /** 图上 NPC 查询（任务临近校验等；查无返回 null；NPC 位置出生后不变，跨 actor 读安全） */
    public NPC getNPCById(int id) {
        return shim.supply("getNPCById", () -> map.getNPCById(id));
    }

    public boolean isTown() {
        return shim.supply("isTown", map::isTown);
    }

    public boolean isCPQMap() {
        return shim.supply("isCPQMap", map::isCPQMap);
    }

    public int getDeathCP() {
        return shim.supply("getDeathCP", map::getDeathCP);
    }

    public int getFieldLimit() {
        return shim.supply("getFieldLimit", map::getFieldLimit);
    }

    // ── 广播/写（run：串行入队，阻塞至完成）──

    public void broadcastMessage(Packet packet) {
        shim.run("broadcastMessage", () -> map.broadcastMessage(packet));
    }

    public void broadcastMessage(CharacterRef source, Packet packet, boolean repeatToSource) {
        shim.run("broadcastMessage", () -> map.broadcastMessage(source, packet, repeatToSource));
    }

    public void broadcastMessage(CharacterRef source, Packet packet, boolean repeatToSource, boolean ranged) {
        shim.run("broadcastMessage", () -> map.broadcastMessage(source, packet, repeatToSource, ranged));
    }

    public void broadcastMessage(Packet packet, Point rangedFrom) {
        shim.run("broadcastMessage", () -> map.broadcastMessage(packet, rangedFrom));
    }

    public void broadcastGMMessage(Packet packet) {
        shim.run("broadcastGMMessage", () -> map.broadcastGMMessage(packet));
    }

    public void broadcastGMMessage(CharacterRef source, Packet packet, boolean repeatToSource) {
        shim.run("broadcastGMMessage", () -> map.broadcastGMMessage(source, packet, repeatToSource));
    }

    public void broadcastNONGMMessage(CharacterRef source, Packet packet, boolean repeatToSource) {
        shim.run("broadcastNONGMMessage", () -> map.broadcastNONGMMessage(source, packet, repeatToSource));
    }

    public void broadcastPacket(CharacterRef source, Packet packet) {
        shim.run("broadcastPacket", () -> map.broadcastPacket(source, packet));
    }

    public void broadcastUpdateCharLookMessage(CharacterRef source, CharacterRef player) {
        shim.run("broadcastUpdateCharLookMessage", () -> map.broadcastUpdateCharLookMessage(source, player));
    }

    public void broadcastSpawnPlayerMapObjectMessage(CharacterRef source, CharacterRef player, boolean enteringField) {
        shim.run("broadcastSpawnPlayerMapObjectMessage", () -> map.broadcastSpawnPlayerMapObjectMessage(source, player, enteringField));
    }

    public void addPlayerPuppet(CharacterRef player) {
        shim.run("addPlayerPuppet", () -> map.addPlayerPuppet(player));
    }

    public void removePlayerPuppet(CharacterRef player) {
        shim.run("removePlayerPuppet", () -> map.removePlayerPuppet(player));
    }

    public void removeMapObject(int num) {
        shim.run("removeMapObject", () -> map.removeMapObject(num));
    }

    public void removeMapObject(MapObject obj) {
        shim.run("removeMapObject", () -> map.removeMapObject(obj));
    }

    /**
     * 离图摘除（载荷键控，RemoveFacts 于 caller/player strand 采集）——任务体零
     * CharacterRef 触达（strict 批次产物）。player 域收尾归 caller 切片。
     */
    public void removePlayer(MapleMap.RemoveFacts facts) {
        shim.run("removePlayer", () -> map.removePlayer(facts));
    }

    /**
     * controller 移交（离图收尾；载荷 = cid——controlled 登记簿在 map 域，任务体按 oid
     * 解析活对象逐只重选举）。上下文截断（既有教义豁免）：controller 移交/换届载荷——
     * 与原 Character.releaseControlledMonsters 内联段同位。
     */
    public void releaseControlledMonsters(int cid) {
        shim.post("release-controlled-monsters", () -> {
            PipelineContext.clear();
            for (int oid : map.releaseControlledMonsters(cid)) {
                Monster monster = map.getMonsterByOid(oid);
                if (monster != null) {
                    monster.aggroRedirectController();
                }
            }
        });
    }

    public void registerCharacterStatUpdate(Runnable r) {
        shim.run("registerCharacterStatUpdate", () -> map.registerCharacterStatUpdate(r));
    }

    // ── 进图/拾取/移动（缝合点语义见各方法注释）──

    /**
     * 进图登记：shim supply 缝合点（调用方阻塞至完成，返回 firstEnter——登记动作的产物，
     * doc/13 §5.2）。party 为 caller 快照（player strand 采集）——任务体对入场者零 Character 访问
     * （id 由 CharacterRef 自持，isHidden 分支按"单机无 GM"删除）。
     */
    public boolean registerPlayer(CharacterRef chr, List<Pet> summonedPets, Party party) {
        return shim.supply("map-registerPlayer", () -> map.registerPlayer(chr, summonedPets, party));
    }

    // ── 入场编舞缝合点（原 finishEnter 段拆出，编舞本体在 CharacterMap.enterMap）：
    //    全部 shim 化——最后一个原位 helper（sendObjectPlacement）已收口，
    //    strict 窗口内零 player strand 直触 map 本体 ──

    /**
     * 入场对象投放：非视野型 spawn 流 + 陈旧 summon 清理 + 视野内 spawn 流（map actor
     * 任务体）。spawn 直发段在任务体内经 {@link CharacterRef#postLegacyPacket} 回 strand。
     * 返回视野新增值条目（调用方回放到本体可见视图，wire 无差）。
     */
    public List<MapView.Entry> sendObjectPlacement(CharacterRef chr, Point pos, int cid, Collection<Summon> ownedSummons) {
        return shim.supply("sendObjectPlacement", () -> {
            // 上下文截断（既有教义豁免，同 onTransitionMobView）：controller 选举/换届载荷
            PipelineContext.clear();
            return map.sendObjectPlacement(chr, pos, cid, ownedSummons);
        });
    }

    /** 入场注册表登记（oid 快照）+  个人商店可空注册 */
    public void registerEnterObjects(CharacterRef chr, int oid, PlayerShop shop) {
        shim.run("registerEnterObjects", () -> map.registerEnterObjects(chr, oid, shop));
    }

    /** summon 投放 + 广播 */
    public void spawnSummon(Summon summon) {
        shim.run("spawnSummon", () -> map.spawnSummon(summon));
    }

    /** summon 投放（owner 排除变体；enterMap 窗口内用——owner 份由 caller 本体直调投递） */
    public void spawnSummonExcludeOwner(Summon summon, CharacterRef owner) {
        shim.run("spawnSummonExcludeOwner", () -> map.spawnSummonExcludeOwner(summon, owner));
    }

    /** 船停靠态（docked 运行时态，map 自读；boat 能力为静态——caller 先查 statics().boat()） */
    public boolean isBoatDocked() {
        return shim.supply("map-isBoatDocked", map::isBoatDocked);
    }

    /** 开赛事件图入口关门（map 内部自读 eventstarted 动态态 + 静态 mapid 判定） */
    public void closeEventJoinPortal() {
        shim.run("closeEventJoinPortal", () -> map.closeEventJoinPortal());
    }

    /** 地图特效初始化数据（mapEffect 为运行时态，直读归 map；client 快照入参） */
    public void sendMapEffectData(Client c) {
        shim.run("sendMapEffectData", () -> map.sendMapEffectData(c));
    }

    /** 拾取落图：调用方 post 的任务体内经 run 调用（onShim 内联，itemLock 契约在任务体侧） */
    public void pickItemDrop(Packet pickupPacket, MapItem mdrop) {
        shim.run("pickItemDrop", () -> map.pickItemDrop(pickupPacket, mdrop));
    }

    /** 角色移动的可见性差集应用（通知类 post）：参数全快照（ref/落点/可见视图 oid 冻结列表） */
    public void handleCharacterMove(CharacterRef chr, Point toPos, List<Integer> visibleOids) {
        shim.post("handleCharacterMove", () -> map.handleCharacterMove(chr, toPos, visibleOids));
    }

    /** 角色移动他人流中继：map actor 内按受众逐连接语义投递（地图决定发谁，包构建归 remote） */
    public void broadcastCharacterMove(int charId, List<MoveElement> movements) {
        shim.post("broadcastCharacterMove", () -> map.broadcastCharacterMove(charId, movements));
    }

    /** 角色完成任务他人流中继：map actor 内按受众逐连接语义投递（同 broadcastCharacterMove 形态） */
    public void broadcastQuestComplete(int charId) {
        shim.post("broadcastQuestComplete", () -> map.broadcastQuestComplete(charId));
    }

    /**
     * mob 控制移动应用（player→map 通知，异步）：载荷为不可变 {@link MapleMap.MoveLifeMsg}
     * （零 player 可变状态导航）。本方法在 player strand 上调用——shim 任务体内不得再绕回
     * ref 方法（ref 是 player 侧句柄，任务体直调 map 本体字段）。
     */
    /**
     * 近战攻击 phase 2 入口（player→map 通知，异步）：载荷 = 不可变 Battle.CloseRangeAttackIntent
     * （attacker 身份 ref + relay 回声字段 + declared 伤害，零活引用）。player strand 上调用。
     */
    public void applyCloseRangeAttack(Battle.CloseRangeAttackIntent intent) {
        shim.post("apply-close-range-attack", () -> map.applyCloseRangeAttack(intent));
    }

    public void onMoveLife(MapleMap.MoveLifeMsg msg) {
        shim.post("move-life", () -> map.onMoveLife(msg));
    }

    /** 切图完成的 mob 视图重建（player→map 通知，异步；载荷 = 本体引用，identity/移交专用） */
    public void onTransitionMobView(CharacterRef chr) {
        shim.post("map-transitionMobView", () -> {
            // 上下文截断（既有教义豁免）：controller 移交/选举载荷 = 合法域上下文
            // （"跨 actor 异步任务在窗口存续期触达 ref 属合法域上下文"），候选遍历的
            // ref 读随任务截断，不因传播而咬。
            PipelineContext.clear();
            map.onTransitionMobView(chr);
        });
    }

    public List<MapItem> updatePlayerItemDropsToParty(int partyid, int charid,
                                                      List<CharacterRef> partyMembers, CharacterRef partyLeaver) {
        return shim.supply("updatePlayerItemDropsToParty",
                () -> map.updatePlayerItemDropsToParty(partyid, charid, partyMembers, partyLeaver));
    }

    public void updatePartyItemDropsToNewcomer(CharacterRef newcomer, List<MapItem> partyItems) {
        shim.run("updatePartyItemDropsToNewcomer", () -> map.updatePartyItemDropsToNewcomer(newcomer, partyItems));
    }
}
