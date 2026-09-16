package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * PET_FOOD 的 GMS083 事件（解码产物，版本词汇，byte/short 合法）。
 * decode 读序即协议序：客户端时间戳（服务端不消费）+ USE 槽位 + itemId。
 * 目标宠物不在包内——选宠由 gameplay 按角色状态完成（no-peek）。
 */
public final class InventoryPacket {

    private InventoryPacket() {
    }

    public record PetFood(short slot, int itemId) {
    }

    public static PetFood decode(ByteBufReader p) {
        p.readInt();
        short slot = p.readShort();
        int itemId = p.readInt();
        return new PetFood(slot, itemId);
    }
}
