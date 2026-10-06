package org.gms.client.scripting.api;

import org.gms.client.character.Character;

/**
 * 脚本 API 白名单——HP 状态面（getHp/updateHp）。方法面白名单 = 脚本语义准入，
 * MP/其它 stats 不在本面（有需要时按域另立方法，不整块放开 stats 组件）。
 *
 * <p><b>线程模型</b>：全部方法在 owning player strand 上调用（脚本宿主串行进
 * Context，与 {@link org.gms.client.scripting.QuestApi} 同纪律）。
 * <b>零可变状态</b>——仅持有 Character 引用；对应 JS 侧 {@code player.stats.getHp()} /
 * {@code player.stats.updateHp(n)}（bind_player.js 分面现取）。
 */
public final class StatsApi {

    private final Character chr;

    public StatsApi(Character chr) {
        this.chr = chr;
    }

    public int getHp() {
        return chr.getHp();
    }

    /** 直接设 HP（绝对值语义，非增减）；帧同步归 stats 域的既有下发路径。 */
    public void updateHp(int hp) {
        chr.updateHp(hp);
    }
}
