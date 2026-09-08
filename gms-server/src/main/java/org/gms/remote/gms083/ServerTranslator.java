package org.gms.remote.gms083;

import java.util.List;

import org.gms.remote.gms083.server.packets.V83Packet;

/**
 * 翻译层出口：把本域积累的语义事件组装为 packet record（不编码）。
 * encode 与日志归 route 层（V83RemoteClient.send/wire）；
 * 可能产出多帧（如背包满提示与操作包并存）。分层职责见 gms-server/doc/package-client.md §6/§7。
 */
public interface ServerTranslator {
    boolean isEmpty();

    /** 组树并清空内部缓冲，交 route 层发送 */
    List<V83Packet> flush();
}
