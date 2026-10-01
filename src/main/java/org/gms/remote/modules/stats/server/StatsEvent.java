package org.gms.remote.modules.stats.server;

import org.gms.remote.ServerEvent;

/** 面板属性 + hp/mp/ap 通知（原 SemanticEvent.Stats）。 */
public record StatsEvent(StatsUpdate update) implements ServerEvent {
}
