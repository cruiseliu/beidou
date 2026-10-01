package org.gms.remote.modules.pet.server;

import org.gms.remote.ServerEvent;

/** 喂食反馈（全图气球）。接收连接即 owner（deliver 侧由 legacy client 派生）。 */
public record PetFoodResponseEvent(int slot, boolean enjoyed, boolean hasChatBalloon) implements ServerEvent {
}
