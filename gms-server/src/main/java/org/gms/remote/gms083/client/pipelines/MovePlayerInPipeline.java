package org.gms.remote.gms083.client.pipelines;

import org.gms.client.Player;
import org.gms.exception.EmptyMovementException;
import org.gms.net.packet.InPacket;
import org.gms.remote.gms083.client.packets.MovePlayerPacket;
import org.gms.remote.gms083.client.ClientInPipeline;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/**
 * MOVE_PLAYER 收包管线：移动语义元素解码（纯函数）→ 地图域 Handler。
 * 应用（chr 写/可见性差集）与广播编排全部在 gameplay/地图侧；本管线无状态。
 * 全服最高频包：queued shim 即 strand canary（串行、ThreadLocal 播种、异常兜底持续被验证）。
 */
public final class MovePlayerInPipeline implements ClientInPipeline {

    public String name() {
        return "move-player-in";
    }

    /** canary 首管线：move 路径已 post 化（MoveMsg 快照过界），strict 窗口内应为零 ref 直调。 */
    @Override
    public boolean strict() {
        return true;
    }

    public void handle(InPacket p, Player player) {
        List<MoveElement> elements;
        try {
            elements = MovePlayerPacket.decode(p);
        } catch (EmptyMovementException e) {
            return;   // 空序列/未识别 command：静默丢（现状语义）
        }
        player.clientEventHandlers().map().movePlayer(elements);
    }
}
