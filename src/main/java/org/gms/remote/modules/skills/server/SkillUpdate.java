package org.gms.remote.modules.skills.server;

/**
 * updateSkill 操作的语义载荷：已获得技能的等级/master/到期新值（skill 域）。
 * expiration 用 {@link org.gms.model.pojo.SkillEntry#PERMANENT} 表示永久。
 * 同一合并域内多条技能更新由实现层合并为同一封包（wire 自带 count 列表）。
 */
public record SkillUpdate(int skillId, int level, int masterLevel, long expiration) {
}
