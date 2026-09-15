package org.gms.remote.modules.basic.server;

/** level 变更事件（BasicModule.updateLevel）。 */
public record UpdateLevelEvent(int level) implements BasicEvent {
}
