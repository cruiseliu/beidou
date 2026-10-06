package org.gms.client;

/**
 * strict 收包窗口种类（迁移 canary 的作用域维度；同角色可多类并存）：
 * <ul>
 *   <li>{@link #STRAND}——map/ref 域本体触达哨：窗口线程内经 CharacterRef / MapleMapRef
 *       直调本体即断言（CharacterRef#notInStrictPipeline / MapleMapRef#assertNotInStrictPipeline）。</li>
 *   <li>{@link #PACKET}——legacy Client 导航哨：窗口线程内 getClient / legacy 发包经
 *       {@link Player#assertNoLegacyClientNavigation} 按全局级别响亮失败。</li>
 * </ul>
 * 开关由 in-route strictWindow（各 InRouter case 按 opcode 增量纳入）与 PLAYER_LOGGEDIN 等
 * 域内大段窗口持有。已知缺口（留作重构测试）：两阶段拆分后窗口只覆盖窗口线程上的同步段，
 * 跨 actor 段（如 battle phase 2 在 map actor 上的伤害管线 legacy 直发 SHOW_MONSTER_HP）
 * 不设窗——哨不咬，待 phase 3 语义化后另立哨。
 */
public enum StrictWindow {
    STRAND,   // ref / map 本体直调哨
    PACKET    // legacy Client 导航哨
}
