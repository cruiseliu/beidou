package org.gms.infra;

/**
 * strict 收包窗口种类（迁移 canary 的作用域维度；同角色可多类并存）：
 * <ul>
 *   <li>{@link #STRAND}——map/ref 域本体触达哨：管线内经 CharacterRef / MapleMapRef
 *       直调本体即断言（CharacterRef#notInStrictPipeline / MapleMapRef#assertNotInStrictPipeline）。</li>
 *   <li>{@link #PACKET}——legacy Client 导航哨：管线内 getClient / legacy 发包经
 *       {@link org.gms.client.Player#assertNoLegacyClientNavigation} 按全局级别响亮失败。</li>
 * </ul>
 * 已知缺口（留作重构测试）：跨 actor 段（如 battle phase 2 在 map actor 上的伤害管线）
 * 靠任务体入口显式截断豁免（PipelineContext.clear()），债清一处撤一处。
 */
public enum StrictWindow {
    STRAND,   // ref / map 本体直调哨
    PACKET    // legacy Client 导航哨
}
