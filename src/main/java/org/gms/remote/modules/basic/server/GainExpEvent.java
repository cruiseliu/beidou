package org.gms.remote.modules.basic.server;

import org.gms.client.character.ExpSource;

/**
 * 获得经验事件（状态应用归 gameplay；本事件承载 exp 数值帧与演出帧的翻译事实）：
 * <ul>
 *   <li>{@code totalExp} → STAT_CHANGED exp 数值帧（绝对总值，unlock=false——对话框期间
 *       不解锁的 legacy 语义刻意保留，与语义域 auto-unlock 的差异为字节等价取舍）。</li>
 *   <li>{@code gain}/{@code source} → 演出帧，显示形态由版本 translator 按 source 决定。</li>
 * </ul>
 *
 * @param gain     演出用经验增量（诅咒减半后的数值，与 legacy 演出口径一致）
 * @param totalExp 应用后的 exp 绝对总值（数值帧载荷）
 * @param source   经验来源
 */
public record GainExpEvent(int gain, long totalExp, ExpSource source) implements BasicEvent {
}
