package org.gms.remote.out.events;

import org.gms.remote.SpUpdate;

/** SP 通知（v83 下由 STAT_CHANGED 后端并包；原 SemanticEvent.Sp）。 */
public record SpEvent(SpUpdate update) implements SemanticEvent {
}
