package org.gms.remote.modules.basic.server;

import org.gms.remote.ServerEvent;

/** 基础标识（jobId/level/exp 等"搭车"字段）通知（原 SemanticEvent.Basic）。 */
public record BasicEvent(BasicUpdate update) implements ServerEvent {
}
