package org.gms.remote.modules.npc.server;

import org.gms.remote.ServerEvent;

/** 过场 UI 图（借 item-inchat 帧发 UI 路径；动作锁解除归 basic 域）。 */
public record ShowInfoEvent(String path) implements ServerEvent {
}
