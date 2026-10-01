package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;

/**
 * 某角色完成了任务（他人流中继，接收方连接视角的语义投递）：他人演出帧的效果码
 * 与形态归版本实现。由地图域在广播时点对每个接收方调用。
 */
public record CharacterQuestCompleteEvent(int charId) implements ServerEvent {
}
