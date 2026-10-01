package org.gms.remote.modules.basic.server;

/** jobId 变更（转职）事件（BasicModule.updateJob）。 */
public record UpdateJobEvent(int jobId) implements BasicEvent {
}
