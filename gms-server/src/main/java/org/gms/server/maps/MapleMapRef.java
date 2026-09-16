package org.gms.server.maps;

import org.gms.client.Client;
import org.gms.client.character.CharacterRef;
import org.gms.client.pet.Pet;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.infra.ActorShim;
import org.gms.net.packet.Packet;
import org.gms.net.server.world.Party;

import org.gms.scripting.event.EventInstanceManager;
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
 * <p><b>接口 = client.character 的用法并集</b>（封闭集，外部调用方经 {@link #unwrap()}）。
 * <b>调用语义（P4 翻转后）</b>：写方法经 {@code shim.run}、有返回值经 {@code shim.supply}
 * （阻塞至 map actor 完成——与 shim 化前的直接调用时序等价，但串行入队）；唯一豁免：
 * {@link #finishEnter} 是 player strand 原位方法（跑脚本，永不入 shim 任务体）。
 * 见 doc/13 迁移记录。
 */
public final class MapleMapRef {

    final MapleMap map;
    final ActorShim shim;

    MapleMapRef(MapleMap map, String shimName) {
        this.map = map;
        this.shim = ActorShim.create(shimName);
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
    public MapleMap unwrap() {
        return map;
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
        return shim.supply("getId", map::getId);
    }

    public Portal getPortal(String portalname) {
        return shim.supply("getPortal", () -> map.getPortal(portalname));
    }

    public Portal getPortal(int portalid) {
        return shim.supply("getPortal", () -> map.getPortal(portalid));
    }

    public Portal getRandomPlayerSpawnpoint() {
        return shim.supply("getRandomPlayerSpawnpoint", map::getRandomPlayerSpawnpoint);
    }

    public Portal findClosestPortal(Point from) {
        return shim.supply("findClosestPortal", () -> map.findClosestPortal(from));
    }

    public Portal findClosestPlayerSpawnpoint(Point from) {
        return shim.supply("findClosestPlayerSpawnpoint", () -> map.findClosestPlayerSpawnpoint(from));
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

    public void removePlayer(CharacterRef chr) {
        shim.run("removePlayer", () -> map.removePlayer(chr));
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

    // ── 入场编舞原位操作（原 finishEnter 段拆出；player strand 原位，§5.4 豁免延续；
    //    编舞本体在 CharacterMap.enterMap，参数全快照，体内零 CharacterRef 方法调用）──

    /**
     * 入场对象投放：非视野型 spawn 流 + 陈旧 summon 清理 + 视野内 spawn 流。
     * 返回视野新增集（调用方回放到本体可见集，wire 无差）。
     */
    public List<MapObject> sendObjectPlacement(Client c, Point pos, int cid, Collection<Summon> ownedSummons) {
        return map.sendObjectPlacement(c, pos, cid, ownedSummons);
    }

    /** 入场注册表登记（oid 快照）+  个人商店可空注册 */
    public void registerEnterObjects(CharacterRef chr, int oid, PlayerShop shop) {
        map.registerEnterObjects(chr, oid, shop);
    }

    /** 龙投放 + 全图广播（source 仅 identity 过滤；isHidden 分支按"单机无 GM"删除） */
    public void spawnDragon(Dragon dragon, Point pos, CharacterRef source) {
        map.spawnDragon(dragon, pos, source);
    }

    /** summon 投放 + 广播（gameplay 路径，窗口外） */
    public void spawnSummon(Summon summon) {
        map.spawnSummon(summon);
    }

    /** summon 投放（owner 排除变体；enterMap 窗口内用——owner 份由 caller 本体直调投递） */
    public void spawnSummonExcludeOwner(Summon summon, CharacterRef owner) {
        map.spawnSummonExcludeOwner(summon, owner);
    }

    /** 船停靠态（docked 运行时态，map 自读；boat 能力为静态——caller 先查 statics().boat()） */
    public boolean isBoatDocked() {
        return shim.supply("map-isBoatDocked", map::isBoatDocked);
    }

    /** 开赛事件图入口关门（map 内部自读 eventstarted 动态态 + 静态 mapid 判定） */
    public void closeEventJoinPortal() {
        map.closeEventJoinPortal();
    }

    /** 地图特效初始化数据（mapEffect 为运行时态，直读归 map；client 快照入参） */
    public void sendMapEffectData(Client c) {
        map.sendMapEffectData(c);
    }

    /** 拾取落图：调用方 post 的任务体内经 run 调用（onShim 内联，itemLock 契约在任务体侧） */
    public void pickItemDrop(Packet pickupPacket, MapItem mdrop) {
        shim.run("pickItemDrop", () -> map.pickItemDrop(pickupPacket, mdrop));
    }

    /** 角色移动的可见性差集应用（通知类 post）：参数全快照（ref/落点/可见集冻结列表） */
    public void handleCharacterMove(CharacterRef chr, Point toPos, List<MapObject> visibleObjs) {
        shim.post("handleCharacterMove", () -> map.handleCharacterMove(chr, toPos, visibleObjs));
    }

    /** 角色移动他人流中继：map actor 内按受众逐连接语义投递（地图决定发谁，包构建归 remote） */
    public void broadcastCharacterMove(int charId, List<MoveElement> movements) {
        shim.post("broadcastCharacterMove", () -> map.broadcastCharacterMove(charId, movements));
    }

    public void onMoveLife(MapleMap.MoveLifeMsg msg) {
        shim.run("onMoveLife", () -> map.onMoveLife(msg));
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
