package org.gms.remote.gms083.client;

import org.gms.client.Player;
import org.gms.net.packet.InPacket;

/**
 * 收包管线（per-module）：decode → translate → 本模块 Handler 槽位直调，
 * 全程在 player strand 上执行（shim 投递，decode→callback 不跨线程）。
 * unlock 回包由各管线按 event 类型自行判定与调用（gameplay 不写）。
 */
public interface ClientInPipeline {

    String name();

    void handle(InPacket p, Player player);

    /**
     * 严格管线开关（迁移 canary，仿 PacketHandler.queued 的按实现翻转模式）：
     * true 时本管线执行窗口内置位 Character.strictMode，期间经 CharacterRef
     * 直调本体（含 unref 解包）即断言失败——定位 map 域同步跨域触达 Character 的
     * 调用链（post 化欠账的探针，doc/16 §4.1）。默认 false = 全部管线暂不启用。
     */
    default boolean strict() {
        return false;
    }
}
