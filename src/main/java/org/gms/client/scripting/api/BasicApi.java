package org.gms.client.scripting.api;

import org.gms.client.Player;
import org.gms.client.character.Character;
import org.gms.client.character.ExpSource;

/**
 * 脚本 API 白名单——基础角色面（isMale/gainExp）。显式列举脚本可用的 API 范围：
 * 方法面白名单 = 脚本语义准入，脚本不得经本类触达 Character 的其余方法面。
 *
 * <p><b>线程模型</b>：脚本宿主把全部执行串行在 owning player strand 上；本类方法经
 * {@link Player#require} 现取 actor context（off-strand 响亮失败），character 派生视图
 * 零存槽。<b>无状态</b>——对应 JS 侧 {@code player.basic.isMale()} /
 * {@code player.basic.gainExp(n, "QUEST")}。
 */
public final class BasicApi {
    private final Character chr;

    public BasicApi(Character chr) {
        this.chr = chr;
    }

    public boolean isMale() {
        return chr.getGender() == 0;
    }

    /**
     * 经验入账；演出形态（白字/飘字/来源）由 source 决定，脚本不感知 wire。
     * source 取 {@link ExpSource} 枚举名字符串，内部手动 valueOf 转换——GraalJS 不做
     * string→enum 自动转换（GraalEnumProbe 实测 "Unsupported target type"）；非法名
     * valueOf 抛 IllegalArgumentException，响亮失败。
     */
    public void gainExp(int gain, String source) {
        chr.gainExp(gain, ExpSource.valueOf(source));
    }
}
