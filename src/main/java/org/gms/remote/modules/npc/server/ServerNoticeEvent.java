package org.gms.remote.modules.npc.server;

import org.gms.remote.ServerEvent;

/** SERVERMESSAGE 通知（self 流；serverNotice(type, message) 形态）。 */
public record ServerNoticeEvent(int type, String message) implements ServerEvent {
}
