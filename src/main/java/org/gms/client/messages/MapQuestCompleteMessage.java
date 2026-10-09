package org.gms.client.messages;

import org.gms.infra.ActorMessage;

/**
 * map → player：视野内某角色完成了任务（他人流中继投递，接收方 strand 语义投递）。
 * 载荷：铸造图 id（接收方权威校验）+ source 身份 id。
 */
public record MapQuestCompleteMessage(int mapId, int charId) implements ActorMessage {
}
