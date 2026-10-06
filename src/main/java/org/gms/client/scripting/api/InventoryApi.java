package org.gms.client.scripting.api;

import org.gms.client.character.Character;

/**
 * 脚本 API 白名单——背包道具面（hasItem/gainItem/gainItems）。方法面白名单 = 脚本语义
 * 准入：脚本只能问"有没有"与"发道具"，不得触碰背包槽位/容器结构。JS 侧命名取
 * {@code hasItem}（Character 门面为 {@code haveItem}，历史拼写不外溢到脚本面）。
 *
 * <p><b>线程模型</b>：全部方法在 owning player strand 上调用（脚本宿主串行进
 * Context，与 {@link org.gms.client.scripting.QuestApi} 同纪律）。
 * <b>零可变状态</b>——仅持有 Character 引用。
 */
public final class InventoryApi {

    private final Character chr;

    public InventoryApi(Character chr) {
        this.chr = chr;
    }

    public boolean hasItem(int itemId) {
        return chr.haveItem(itemId);
    }

    /** 发 1 件；背包满返回 false（脚本侧据此走 inv_full 对白分支）。 */
    public boolean gainItem(int itemId) {
        return chr.gainItem(itemId);
    }

    public boolean gainItem(int itemId, int quantity) {
        return chr.gainItem(itemId, quantity);
    }

    /** 批量发 {@code [itemId, 数量]} 对；任一失败即整批失败并回滚（语义同 Character）。 */
    public boolean gainItems(int[][] entries) {
        return chr.gainItems(entries);
    }
}
