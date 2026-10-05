package org.gms.remote.gms083.client.blocks;

import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * USE_ITEM / USE_RETURN_SCROLL 共用收包块（收侧对偶 server 侧 ItemBlock）：两 opcode
 * 同构 wire——客户端时间戳（服务端不消费）+ USE 槽位 + itemId。解码归本块，packet
 * class 各自持有 opcode 名并委托（每 opcode 一个 packet class，doc/package-client.md §1）。
 * 消耗类别（解除药水/回城卷/普通药水）不在包内——由 gameplay 按 itemId 分流（no-peek）。
 */
public record UseItemBlock(short slot, int itemId) {

    public static UseItemBlock decode(ByteBufReader p) {
        p.readInt();
        return new UseItemBlock(p.readShort(), p.readInt());
    }
}
