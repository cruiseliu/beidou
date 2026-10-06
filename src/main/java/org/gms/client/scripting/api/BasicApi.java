package org.gms.client.scripting.api;

import org.gms.client.Player;
import org.gms.client.character.ExpSource;

/**
 * 脚本 API 白名单——基础角色面（isMale/gainExp）。显式列举脚本可用的 API 范围：
 * 方法面白名单 = 脚本语义准入，脚本不得经本类触达 Character 的其余方法面。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败），character 派生视图
 * 零存槽。<b>无状态</b>——对应 JS 侧 {@code player.basic.isMale()} /
 * {@code player.basic.gainExp(n, ExpSource.X)}。
 */
public final class BasicApi {

    public boolean isMale() {
        return Player.require("BasicApi.isMale").character().isMale();
    }

    /** 经验入账；演出形态（白字/飘字/来源）由 ExpSource 决定，脚本不感知 wire。 */
    public void gainExp(int gain, ExpSource source) {
        Player.require("BasicApi.gainExp").character().gainExp(gain, source);
    }
}
