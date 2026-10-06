package org.gms.client.scripting.api;

import org.gms.client.Player;

/**
 * 脚本 API 白名单——HP 状态面（getHp/updateHp）。方法面白名单 = 脚本语义准入，
 * MP/其它 stats 不在本面（有需要时按域另立方法，不整块放开 stats 组件）。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败），character 派生视图
 * 零存槽。<b>无状态</b>——对应 JS 侧 {@code player.stats.getHp()} /
 * {@code player.stats.updateHp(n)}。
 */
public final class StatsApi {

    public int getHp() {
        return Player.require("StatsApi.getHp").character().getHp();
    }

    /** 直接设 HP（绝对值语义，非增减）；帧同步归 stats 域的既有下发路径。 */
    public void updateHp(int hp) {
        Player.require("StatsApi.updateHp").character().updateHp(hp);
    }
}
