package org.gms.remote.modules.message.server;

import org.gms.remote.ServerEvent;

/** 教学提示 balloon（PLAYER_HINT；width/height 为 balloon 版面参数，归一在版本 wire）。 */
public record ShowHintEvent(String message, int width, int height) implements ServerEvent {
}
