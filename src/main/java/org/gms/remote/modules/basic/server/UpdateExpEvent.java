package org.gms.remote.modules.basic.server;

/** exp 变更事件（BasicModule.updateExp）。 */
public record UpdateExpEvent(long exp) implements BasicEvent {
}
