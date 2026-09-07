package org.gms.remote.v83.in;

import org.gms.client.Client;
import org.gms.client.Player;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.InPacket;
import org.gms.remote.in.ModuleInDispatch;
import org.gms.remote.ModuleIn;
import org.gms.remote.in.events.ClientEvent;
import org.gms.remote.v83.in.packet.PetPacket;
import org.gms.remote.v83.in.translate.PetTranslator;

import java.util.List;

/**
 * in-op shim（收包管线入口）：注册到的 opcode 全部经本 handler 接入，
 * 单实例多 opcode 注册（queued() 按类翻绿 = 本类收包全体 strand 化）。
 *
 * <p><b>约束</b>：decode → callback 全程在 player strand 上执行（本方法即 strand 任务）；
 * 管线尾经 actor 导航取得 {@link ModuleIn} 聚合并分派——该导航是语义声明的入口交接，
 * 非 Character 数据 peek（v83.in 各层只读包与语义接口）。
 *
 * <p>解码/翻译/分派的异常由 strand fail-safe 吞掉记日志（与旧 dispatch 模板同级容错）。
 */
public final class V83RemoteClientHandler extends AbstractPacketHandler {

    private final RecvOpcode opcode;

    public V83RemoteClientHandler(RecvOpcode opcode) {
        this.opcode = opcode;
    }

    @Override
    public boolean queued() {
        return true;   // strand 迁移 M2：in-op 收包管线（decode→callback 单 strand）
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        c.getStrand().run("in-" + opcode, () -> {
            Player player = Player.require("in " + opcode);
            List<ClientEvent> events = switch (opcode) {
                case SPAWN_PET -> PetTranslator.toEvents(PetPacket.decodeSpawnPet(p));
                case PET_FOOD -> PetTranslator.toEvents(PetPacket.decodePetFood(p));
                default -> throw new IllegalStateException("未接入的收包 opcode: " + opcode);
            };
            if (player.character() == null) {   // 转换窗口守卫（validateState 后角色理论上已挂）
                return;
            }
            ModuleIn in = player.character().in();
            for (ClientEvent e : events) {
                ModuleInDispatch.dispatch(e, in);
            }
        });
    }
}
