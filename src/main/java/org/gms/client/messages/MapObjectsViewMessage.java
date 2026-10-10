package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;

import java.util.List;

/**
 * map → player：地图对象可见集差集（值化）。原 addVisibleMapObject/removeVisibleMapObject
 * （map actor 线程直写 Character 活引用集合）与 applyVisibleMapObjects（活引用列表回投）
 * 的替代通道——跨域载荷只剩 (oid, 类型, 模板 id) 值，无活 MapObject 引用。
 * {@code monsterViews} = 怪物条目随行的值快照（授控帧的数据源，player 域自持），
 * 仅 MONSTER 类 add 携带；remove 侧注销归 {@code MapView.removeMonster}（apply 钩子）。
 * mapId 供接收方 actor 校验自身所在图（异步边界：投递解析基于 map 的陈旧玩家表，
 * 切图竞态下的迟到差集由 Player actor 丢弃）。
 */
public record MapObjectsViewMessage(int mapId, List<MapView.Entry> adds, List<Integer> removes,
                                    List<MapView.MonsterView> monsterViews) implements ActorMessage {

    public MapObjectsViewMessage(int mapId, List<MapView.Entry> adds, List<Integer> removes) {
        this(mapId, adds, removes, List.of());
    }

    public MapObjectsViewMessage {
        adds = List.copyOf(adds);
        removes = List.copyOf(removes);
        monsterViews = List.copyOf(monsterViews);
    }
}
