package org.gms.remote.out.events;

import org.gms.remote.StatsUpdate;

/** 面板属性 + hp/mp/ap 通知（原 SemanticEvent.Stats）。 */
public record StatsEvent(StatsUpdate update) implements SemanticEvent {
}
