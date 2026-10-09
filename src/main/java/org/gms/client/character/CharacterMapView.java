package org.gms.client.character;

/**
 * map 域只读角色视图快照：player actor 在任务边界整体发布到 {@link CharacterRef}，
 * map 域经 ref 免哨读取。不可变 record，copy-on-write 发布（volatile 可见性）。
 *
 * <p>迁移期先含 level 验证架构；目标吸收 map 用到的全部/大多数字段
 * （isLoggedInWorld、isHidden、targetHpBarHash 等，按哨咬点台账逐个纳入）。
 * 一致性语义：任务边界最终一致——map 读到最近一次发布值，对选举/经验份额/leech/
 * 队伍归属等近似用途无害；角色域内部一律读本体系（视图不服务 owner 自身）。
 * partyId = -1 表示无队伍（组件域原值透传；Party id 恒正，成员资格一律 > 0 判定）。
 */
public record CharacterMapView(int level, int partyId) {
}
