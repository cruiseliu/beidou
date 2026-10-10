package org.gms.client.character;

import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObjectType;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色侧地图对象视图（player actor 域）：「客户端被告知的对象集」的服务端镜像——
 * map actor 每次向本角色投递 spawn/destroy 包时，同步以值消息
 * （{@link org.gms.client.messages.MapObjectsViewMessage}）登记/注销 oid → 值快照。
 *
 * <p>取代原 {@code visibleMapObjects} 活引用集合（map actor 线程直写
 * {@code Set<MapObject>} 的跨域活引用通道）：值化后过界只剩 oid 与对象值投影。
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
     * 地图对象值快照：type + 模板 id + 位置 + 可见标记。id 语义随 type（MONSTER=mobId、
     * ITEM=itemId、REACTOR=reactorId、NPC=npcId；其余类型暂记 0——按需补）。将来演进为
     * MapObjectView 接口（每类型自带语义载荷），届时本记录退役。
     *
     * <p>{@code visible} = player 域状态：「已把该对象转发给 client」的标记——移动差集
     * （进入/离开视野 → spawn/destroy）由它驱动。当前 map 侧按范围预过滤，登记即可见
     * （恒 true）；可见判定权移交 player 域后，由 apply 侧按 (自身位置, position) 赋值/翻转。
     */
    public record MapObjectInfo(MapObjectType type, int id, Point position, boolean visible) {

        public MapObjectInfo {
            position = new Point(position);   // 防御性拷贝（Point 可变）
        }
    }

    /** 带 oid 的登记条目（值消息与入场 placement seed 共用载荷） */
    public record Entry(int oid, MapObjectInfo info) {
    }

    /**
     * 怪物值快照（player 域自持的怪物视图）：落地/授控帧所需的全部 mob 字段，
     * map 域在 post 时点冻结（快照后 mob 活状态不再被读——Monster 不跨界）。
     * stati 为副本（{@code new HashMap<>} 保序：freeze 侧过滤/toMap 的迭代序与 legacy
     * 逐位一致）；linkedParentOid = post 时点父怪关联判定（0 = 无父怪或父不在/亡）。
     *
     * <p>快照时序 = 落地帧的 legacy 预构建读点（post 前构建）；授控帧的 legacy 桥体读点
     * 在 dispatch（晚于本快照）——controller 字节由 {@code controlled} 事实推导（授控
     * 语境快照先于 setController? 否：setController 先于 post，同线程程序序，恒为
     * controlled=true → kind 1），消除了 legacy 读到 null 的竞态窗。
     */
    public record MonsterView(
            int oid, int mobId, boolean controlled,
            Point position, byte stance, short fh, byte team,
            Map<MonsterStatus, MonsterStatusEffect> stati,
            int linkedParentOid) {

        public MonsterView {
            position = new Point(position);   // 防御性拷贝（Point 可变）
            stati = new HashMap<>(stati);     // 副本保序（freeze 侧再过滤/toMap）
        }

        /** 快照工厂（map 域 post 时点调用；父怪关联在此一并判定） */
        public static MonsterView of(Monster mob) {
            int linkedParent = 0;
            if (mob.getParentMobOid() != 0) {
                Monster parentMob = mob.getMap().getMonsterByOid(mob.getParentMobOid());
                if (parentMob != null && parentMob.isAlive()) {
                    linkedParent = mob.getParentMobOid();
                }
            }
            return new MonsterView(mob.getObjectId(), mob.getId(), mob.getController() != null,
                    mob.getPosition(), (byte) mob.getStance(), (short) mob.getFh(), (byte) mob.getTeam(),
                    mob.getStati(), linkedParent);
        }
    }

    private final Map<Integer, MapObjectInfo> objects = new ConcurrentHashMap<>();
    private final Map<Integer, MonsterView> monsters = new ConcurrentHashMap<>();

    /** 控制中的怪物 oid（客户端控制位镜像）：授控值消息登记，stop/死亡/视野差集注销 */
    private final Set<Integer> controlled = ConcurrentHashMap.newKeySet();

    /** 进图重建（enterMap 编舞开头，player strand）：清空全表 */
    public void reset() {
        objects.clear();
        monsters.clear();
        controlled.clear();
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

    /** 怪物视图登记（落地值消息应用；幂等） */
    public void putMonster(MonsterView view) {
        monsters.put(view.oid(), view);
    }

    /** 怪物视图注销（死亡/视野差集；幂等） */
    public void removeMonster(int oid) {
        monsters.remove(oid);
    }

    /** 怪物视图读（player 域自持；MOVE_LIFE 预滤等的数据源） */
    public MonsterView monster(int oid) {
        return monsters.get(oid);
    }

    /**
     * 对象出视图的统一注销：条目 + 怪物值视图 + 控制位一并清（死亡/视野差集/切图重建
     * 全收敛 {@link org.gms.client.Character#applyMapObjectsView} removes 钩）。
     * 控制位镜像客户端事实——受控对象被 destroy 即不再受控，与服务端粘滞选举解耦。
     */
    public void removeObject(int oid) {
        objects.remove(oid);
        monsters.remove(oid);
        controlled.remove(oid);
    }

    /** 控制位登记（授控值消息应用；幂等——autoAggro 刷新重发不重不漏） */
    public void putControlled(int oid) {
        controlled.add(oid);
    }

    /** 控制位注销（stop 值消息应用；幂等） */
    public void removeControlled(int oid) {
        controlled.remove(oid);
    }

    /** 是否正在控制该怪（MOVE_LIFE 预滤等 player 域判定用） */
    public boolean isControlling(int oid) {
        return controlled.contains(oid);
    }

    /** 控制集快照（oid 列表） */
    public List<Integer> controlledSnapshot() {
        return new ArrayList<>(controlled);
    }
}
