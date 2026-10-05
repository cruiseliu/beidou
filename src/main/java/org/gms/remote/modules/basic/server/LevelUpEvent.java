package org.gms.remote.modules.basic.server;

/**
 * 升级事件（levelUp：升级 + takeexp/满级清零后的最终值）：STAT_CHANGED level + exp
 * 双条目合并一帧（unlock 由语义域 auto-unlock 置位）。
 *
 * @param level 升级后的等级
 * @param exp   应用后的 exp（满级 = 0）
 */
public record LevelUpEvent(int level, long exp) implements BasicEvent {
}
