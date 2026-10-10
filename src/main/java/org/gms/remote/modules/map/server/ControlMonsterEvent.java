package org.gms.remote.modules.map.server;

import org.gms.remote.ServerEvent;
import org.gms.server.life.Monster;

/**
 * 授控（S→C 语义事件）：接收方连接开始控制该怪。携带 mob 活引用，
 * 版本 route 在 freeze 时点物化全身帧（快照在入域时点抽取，翻译只读快照）。
 */
public record ControlMonsterEvent(Monster mob, boolean immediateAggro) implements ServerEvent {
}
