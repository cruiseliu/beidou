package org.gms.remote.modules.skills.server;

import org.gms.remote.ServerEvent;

/** SP 通知（v83 下由 STAT_CHANGED 后端并包；原 SemanticEvent.Sp）。 */
public record SpEvent(SpUpdate update) implements ServerEvent {
}
