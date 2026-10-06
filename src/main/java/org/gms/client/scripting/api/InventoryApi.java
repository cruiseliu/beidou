package org.gms.client.scripting.api;

import org.gms.client.Player;

/**
 * 脚本 API 白名单——背包道具面（hasItem/gainItem/gainItems）。方法面白名单 = 脚本语义
 * 准入：脚本只能问"有没有"与"发道具"，不得触碰背包槽位/容器结构。JS 侧命名取
 * {@code hasItem}（Character 门面为 {@code haveItem}，历史拼写不外溢到脚本面）。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败），character 派生视图
 * 零存槽。<b>无状态</b>——对应 JS 侧 {@code player.inventory.hasItem(id)} 等三个名字。
 */
public final class InventoryApi {

    public boolean hasItem(int itemId) {
        return Player.require("InventoryApi.hasItem").character().haveItem(itemId);
    }

    /** 发 1 件；背包满返回 false（脚本侧据此走 inv_full 对白分支）。 */
    public boolean gainItem(int itemId) {
        return Player.require("InventoryApi.gainItem").character().gainItem(itemId);
    }

    public boolean gainItem(int itemId, int quantity) {
        return Player.require("InventoryApi.gainItem").character().gainItem(itemId, quantity);
    }

    /** 批量发 {@code [itemId, 数量]} 对；任一失败即整批失败并回滚（语义同 Character）。 */
    public boolean gainItems(int[][] entries) {
        return Player.require("InventoryApi.gainItems").character().gainItems(entries);
    }
}
