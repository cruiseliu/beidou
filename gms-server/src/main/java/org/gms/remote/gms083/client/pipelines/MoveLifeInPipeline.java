package org.gms.remote.gms083.client.pipelines;

import org.gms.client.Player;
import org.gms.exception.EmptyMovementException;
import org.gms.net.packet.InPacket;
import org.gms.remote.gms083.client.packets.MoveLifePacket;
import org.gms.remote.modules.map.client.MoveLife;
import org.gms.remote.gms083.client.ClientInPipeline;

/**
 * MOVE_LIFE 收包管线：mob 控制移动语义解码（纯函数）→ 地图域 Handler。
 * aggro/位置应用/ack/中继全部在地图域（map actor 任务体）；本管线无状态。
 */
public final class MoveLifeInPipeline implements ClientInPipeline {

    public String name() {
        return "move-life-in";
    }

    public void handle(InPacket p, Player player) {
        MoveLife life;
        try {
            life = MoveLifePacket.decode(p);
        } catch (EmptyMovementException e) {
            return;   // 空序列/未识别 command：静默丢（现状语义）
        }
        player.clientEventHandlers().map().moveLife(life);
    }
}
