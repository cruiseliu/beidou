package org.gms.remote.modules.basic.server;

/**
 * 获得经验演出事件（状态应用归 gameplay——exp 数值帧走 updateSingleStat 既有路径；
 * 本事件只承载演出意图，source → 显示形态归版本 translator）。
 *
 * @param gain   演出用经验增量（诅咒减半后的数值，与 legacy 演出口径一致）
 * @param source 经验来源
 */
public record GainExpEvent(int gain, ExpSource source) implements BasicEvent {
}
