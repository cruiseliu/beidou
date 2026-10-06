package org.gms.client.scripting.api;

import org.gms.client.character.Character;
import org.gms.client.character.ExpSource;

/**
 * 脚本 API 白名单——基础角色面（isMale/gainExp）。显式列举脚本可用的 API 范围：
 * 方法面白名单 = 脚本语义准入，脚本不得经本类触达 Character 的其余方法面。
 *
 * <p><b>线程模型</b>：全部方法在 owning player strand 上调用（脚本宿主串行进
 * Context，与 {@link org.gms.client.scripting.QuestApi} 同纪律）。
 * <b>零可变状态</b>——仅持有 Character 引用；对应 JS 侧 {@code player.basic.isMale()} /
 * {@code player.basic.gainExp(n, ExpSource.X)}（bind_player.js 分面现取）。
 */
public final class BasicApi {

    private final Character chr;

    public BasicApi(Character chr) {
        this.chr = chr;
    }

    public boolean isMale() {
        return chr.isMale();
    }

    /** 经验入账；演出形态（白字/飘字/来源）由 ExpSource 决定，脚本不感知 wire。 */
    public void gainExp(int gain, ExpSource source) {
        chr.gainExp(gain, source);
    }
}
