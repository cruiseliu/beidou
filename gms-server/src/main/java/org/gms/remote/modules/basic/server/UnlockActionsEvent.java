package org.gms.remote.modules.basic.server;

import org.gms.remote.ServerEvent;

/** 解除客户端动作锁（原 SemanticEvent.UnlockActions）。 */
public record UnlockActionsEvent() implements ServerEvent {
}
