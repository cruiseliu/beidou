package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.client.blocks.UseItemBlock;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * USE_RETURN_SCROLL 收包 codec（v83）：回程卷使用。wire 与 USE_ITEM 同构——解码下沉
 * 共用块 {@link UseItemBlock}（每 opcode 一个 packet class，doc/package-client.md §1）。
 */
public final class UseReturnScrollPacket {

    private UseReturnScrollPacket() {
    }

    public static UseItemBlock decode(ByteBufReader p) {
        return UseItemBlock.decode(p);
    }
}
