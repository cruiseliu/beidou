package org.gms.remote.modules.pet.server;

import org.gms.remote.ServerEvent;

/** 改名演出（全图）。接收连接即 owner（deliver 侧由 legacy client 派生）。 */
public record PetNameChangeEvent(String newName, int slot) implements ServerEvent {
}
