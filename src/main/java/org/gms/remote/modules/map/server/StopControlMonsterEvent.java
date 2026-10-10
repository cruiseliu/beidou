package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

/**
 * 收回怪物控制（S→C 语义事件）：接收方连接不再控制该怪。
 * 帧（CONTROL 0x00 + oid）归版本实现，无需快照。
 */
public record StopControlMonsterEvent(int oid) implements ServerEvent {
}
