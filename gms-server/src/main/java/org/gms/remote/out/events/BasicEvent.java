package org.gms.remote.out.events;

import org.gms.remote.BasicUpdate;

/** 基础标识（jobId/level/exp 等"搭车"字段）通知（原 SemanticEvent.Basic）。 */
public record BasicEvent(BasicUpdate update) implements SemanticEvent {
}
