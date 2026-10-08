package org.gms.client.character;

import org.gms.server.maps.MapObjectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色侧地图对象视图（player actor 域）：「客户端被告知的对象集」的服务端镜像——
 * map actor 每次向本角色投递 spawn/destroy 包时，同步以值消息
 * （{@link org.gms.client.messages.MapObjectsViewMessage}）登记/注销 oid → (类型, 模板 id)。
 *
 * <p>取代原 {@code visibleMapObjects} 活引用集合（map actor 线程直写
 * {@code Set<MapObject>} 的跨域活引用通道）：值化后过界只剩 oid 与模板 id。
 * 读侧三类：player strand 内查询（攻击目标 oid → mobId，战斗 phase 1 用）、
 * ref 值读（map 侧可见性差集判定；CHM 并发容忍语义与原集合一致）、
 * oid 快照（移动差集事实随 move 消息回传 map）。
 *
 * <p>一致性：消息带 mapId，接收方（MessageDispatcher）以 chr.getMapId() 校验，
 * 切图竞态下的迟到差集丢弃；进图编舞开头 reset。stale 残留自愈——oid 为每图实例
 * 单调分配（~11.5 亿号才回绕），正常 uptime 内不复用，死 oid 条目永不与新对象撞号。
 */
public final class MapView {

    /**
     * 地图对象值快照（临时过渡形态）：type + 模板 id（MONSTER=mobId、ITEM=itemId、
     * REACTOR=reactorId、NPC=npcId；其余类型暂记 0——按需补）。将来演进为
     * MapObjectView 接口（每类型自带语义载荷），届时本记录退役。
     */
    public record MapObjectInfo(MapObjectType type, int id) {
    }

    /** 带 oid 的登记条目（值消息与入场 placement seed 共用载荷） */
    public record Entry(int oid, MapObjectInfo info) {
    }

    private final Map<Integer, MapObjectInfo> objects = new ConcurrentHashMap<>();

    /** 进图重建（enterMap 编舞开头，player strand）：清空全表 */
    public void reset() {
        objects.clear();
    }

    /** 值登记（值消息应用 / player 侧门直poke 共用）；幂等（oid 键覆盖） */
    public void add(int oid, MapObjectInfo info) {
        objects.put(oid, info);
    }

    /** 批量登记（差集/seed 应用），幂等 */
    public void addAll(Collection<Entry> entries) {
        for (Entry e : entries) {
            objects.put(e.oid(), e.info());
        }
    }

    /** 值注销（幂等） */
    public void remove(int oid) {
        objects.remove(oid);
    }

    /** player strand 内查询（目标 oid → 对象快照；未知/stale 返回 null） */
    public MapObjectInfo get(int oid) {
        return objects.get(oid);
    }

    /** 差集判定读（map 侧经 ref 调用；CHM 并发容忍与原集合一致） */
    public boolean contains(int oid) {
        return objects.containsKey(oid);
    }

    /** oid 快照（移动差集事实回传用） */
    public List<Integer> oidSnapshot() {
        return new ArrayList<>(objects.keySet());
    }
}
