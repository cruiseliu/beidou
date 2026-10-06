package org.gms.remote.modules.message.server;

import org.gms.remote.ServerEvent;

/** 过场 UI 图（借 item-inchat 帧发 WZ UI 路径；unlock STAT_CHANGED 由 MessageRouter 随语义拼装）。 */
public record ShowInfoEvent(String path) implements ServerEvent {
}
