package org.gms.remote.modules.map.client;

import org.gms.net.packet.Packet;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.util.List;

/**
 * 语义模块：地图域（移动中继）。
 *
 * <p>S→C 视图：移动中继编码——元素序列对称重放为 wire 包（成品，供调用方投递；
 * 本模块不决定接收方，收播过滤归地图侧）。C→S Handler：玩家移动语义入口。
 */
public interface MapModule {

    /**
     * 移动中继编码（他人流广播的成品包）：int charId + 元素序列对称重放。
     * 与历史 PacketCreator.movePlayer 逐字节一致（对称 codec，round-trip 验证）。
     */
    Packet movePlayer(int charId, List<MoveElement> elements);

    /** 移动语义入口（player actor strand 上执行；元素由 gms083 纯解码产出）。 */
    interface Handler {
        void movePlayer(List<MoveElement> elements);
    }
}
