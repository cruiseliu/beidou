package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * USE_ITEM 的 GMS083 事件（解码产物，版本词汇，byte/short 合法）。
 * decode 读序即协议序：客户端时间戳（服务端不消费）+ USE 槽位 + itemId。
 * 消耗类别（解除药水/回城卷/普通药水……）不在包内——由 gameplay 按 itemId 分流（no-peek）。
 */
public final class UseItemPacket {

    private UseItemPacket() {
    }

    public record UseItem(short slot, int itemId) {
    }

    public static UseItem decode(ByteBufReader p) {
        p.readInt();
        short slot = p.readShort();
        int itemId = p.readInt();
        return new UseItem(slot, itemId);
    }
}
