package org.gms.remote.gms083.client;

import org.gms.client.Client;
import org.gms.client.Player;
import org.gms.client.character.Character;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;

/**
 * in-op shim（收包管线入口）：注册到的 opcode 全部经本 handler 委托给对应管线，
 * 单管线可承载一族 opcode（queued() 按类翻绿 = 本类收包全体 strand 化）。
 *
 * <p><b>约束</b>：decode → callback 全程在 player strand 上执行（本方法即 strand 任务）。
 * 管线内解码/翻译/分派的异常由 strand fail-safe 吞掉记日志（与旧 dispatch 模板同级容错）。
 */
public final class V83RemoteClientHandler extends AbstractPacketHandler {

    private final ClientInPipeline pipeline;

    public V83RemoteClientHandler(ClientInPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public boolean queued() {
        return true;   // strand 迁移：in-op 收包管线（decode→callback 单 strand）
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        c.getStrand().run("in-" + pipeline.name(), () -> {
            Player player = Player.require("in " + pipeline.name());
            Character chr = player.character();
            if (chr == null) {   // 转换窗口守卫（validateState 后角色理论上已挂）
                return;
            }
            boolean strict = pipeline.strict();
            if (strict) {   // strict 管线窗口：置位 canary，CharacterRef 直调本体即断言（fail-safe 记日志）
                chr.setStrictMode(true);
            }
            try {
                pipeline.handle(p, player);
            } finally {
                if (strict) {
                    chr.setStrictMode(false);
                }
            }
        });
    }
}
