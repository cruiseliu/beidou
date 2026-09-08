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
}
