package org.gms.remote.gms083.server.events;

import org.gms.remote.ServerEventBase;

import java.awt.Point;

/**
 * freeze 的产物：掉落的所有权演出在入域时点物化——viewer 域判定（本人/同队/15s
 * 窗口）→ dropType 升格，owner 标识解析（有队伍用队伍 id 否则个人 id）。
 * dropTime 已被判定消费，不再携带。
 */
public record FrozenItemDropEvent(
        int oid, int itemId, int meso, int ownerId,
        byte dropType, boolean playerDrop, int dropperOid, long itemExpiration,
        Point dropfrom, Point dropto, byte mod) implements ServerEventBase {
}
