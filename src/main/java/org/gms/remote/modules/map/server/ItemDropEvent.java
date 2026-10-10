package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

import java.awt.Point;

/**
 * 掉落物落地（S→C 语义事件）：载荷 = 掉落快照的全部包构值（无活引用）。
 * quest 过滤与视野判定归调用方 viewer 域；所有权演出（dropType 升格 + owner
 * 标识解析）与过期换算归版本实现（freeze 时点物化）。
 */
public record ItemDropEvent(
        int oid, int itemId, int meso,
        int characterOwnerId, int partyOwnerId, long dropTime, long itemExpiration,
        byte dropType, boolean playerDrop, int dropperOid,
        Point dropfrom, Point dropto, byte mod) implements ServerEvent {
}
