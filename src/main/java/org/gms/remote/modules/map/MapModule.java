package org.gms.remote.modules.map;

import org.gms.remote.AbstractModule;
import org.gms.remote.modules.map.client.ChangeMapEvent;
import org.gms.remote.modules.map.client.MonsterMove;
import org.gms.remote.modules.map.client.MoveLife;
import org.gms.remote.modules.map.client.ReviveHereEvent;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.remote.modules.map.server.AckMoveMonsterEvent;
import org.gms.remote.modules.map.server.UpdateMonsterHpEvent;
import org.gms.remote.modules.map.server.ChangeMapServerEvent;
import org.gms.remote.modules.map.server.CharacterMoveEvent;
import org.gms.remote.modules.map.server.CharacterQuestCompleteEvent;
import org.gms.remote.modules.map.server.MonsterMoveEvent;

import java.awt.Point;
import java.util.List;

/**
 * 语义模块：地图域（移动中继）。
 *
 * <p>S→C 视图：移动中继语义事件——元素序列由版本 router 对称重放为 wire 包；本模块不决定
 * 接收方，收播过滤归地图侧（地图决定发谁，包的构建归 remote，§3）。C→S Handler：玩家/mob
 * 控制移动语义入口。
 */
public abstract class MapModule extends AbstractModule {

    /**
     * 某角色移动了（他人流中继，接收方连接视角的语义投递）。
     * 由地图域在广播时点对每个接收方调用。
     */
    public final void characterMove(int charId, List<MoveElement> movements) {
        post(new CharacterMoveEvent(charId, movements));
    }

    /**
     * 某角色完成了任务（他人流中继，接收方连接视角的语义投递；演出形态归版本实现）。
     * 由地图域在广播时点对每个接收方调用。
     */
    public final void characterQuestComplete(int charId) {
        post(new CharacterQuestCompleteEvent(charId));
    }

    /**
     * mob 移动 ack（controller 连接直发；载荷依赖 mob 状态，由地图域在 map actor
     * 任务体内调用）。与历史 PacketCreator.moveMonsterResponse 逐字节一致。
     */
    public final void ackMoveMonster(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel) {
        post(new AckMoveMonsterEvent(oid, moveid, currentMp, useSkills, skillId, skillLevel));
    }

    /**
     * 某怪物 HP 变化了（他人流/攻击者投递，接收方连接视角的语义通知；演出形态归版本实现）。
     * 由地图域在伤害管线内对每个接收方调用。
     */
    public final void updateMonsterHp(int oid, int hpPercent) {
        post(new UpdateMonsterHpEvent(oid, hpPercent));
    }

    /**
     * 某怪物移动了（他人流中继，接收方连接视角的语义投递）。
     * 由地图域在广播时点对每个受众调用。
     */
    public final void monsterMove(MonsterMove move) {
        post(new MonsterMoveEvent(move));
    }

    /**
     * 玩家换图主包（服务端权威换图的执行结果；落地 = 目标图 spawnPoint 传送门）。
     *
     * @param mapId      目标图 id
     * @param spawnPoint 落点传送门号
     * @param hp         落地血量快照
     */
    public final void changeMapServer(int mapId, int spawnPoint, int hp) {
        post(new ChangeMapServerEvent(mapId, spawnPoint, hp, null));
    }

    /**
     * 玩家换图主包（坐标落地变体）。
     *
     * @param mapId          目标图 id
     * @param hp             落地血量快照
     * @param spawnPosition  落点坐标
     */
    public final void changeMapServerAt(int mapId, int hp, Point spawnPosition) {
        post(new ChangeMapServerEvent(mapId, -1, hp, spawnPosition));
    }

    /** 移动语义入口（player actor strand 上执行；元素由 gms083 纯解码产出）。 */
    public interface Handler {
        void movePlayer(List<MoveElement> elements);

        /** mob 控制移动语义入口（MOVE_LIFE；player strand 快照过界，map 域应用）。 */
        void moveLife(MoveLife life);

        /** 切图完成确认语义入口（PLAYER_MAP_TRANSFER；player 侧标志/beacon + map 域 mob 视图重建）。 */
        void mapTransition();

        /** 脚本传送门入口（CHANGE_MAP_SPECIAL；门校验与门脚本执行归 gameplay 地图域）。 */
        void enterPortal(String portalName);

        /**
         * 走传送门/白名单 warp 意图入口（CHANGE_MAP mode=0；复活分支、门校验与走门编排
         * 归 gameplay 地图域）。
         */
        void changeMap(ChangeMapEvent event);

        /**
         * 原地复活意图入口（CHANGE_MAP mode=1，死亡弹窗；转盘持有校验与复活路径归
         * gameplay 地图域）。
         */
        void reviveHere(ReviveHereEvent event);
    }
}
