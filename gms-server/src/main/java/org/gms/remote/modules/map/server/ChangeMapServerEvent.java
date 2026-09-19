package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

import java.awt.Point;

/**
 * 玩家换图主包语义事件（SET_FIELD warp 形态，服务端权威换图的执行结果）。
 * spawnPosition = null：按目标图 spawnPoint 传送门落地；非 null：按坐标落地
 * （wire 侧使用坐标形态标记）。hp 为落地血量快照——语义事实随事件走，
 * 版本实现不回读 Character。
 */
public record ChangeMapServerEvent(int mapId, int spawnPoint, int hp, Point spawnPosition) implements ServerEvent {
}
