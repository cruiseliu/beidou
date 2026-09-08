package org.gms.remote.gms083.client.packets;

import org.gms.net.packet.InPacket;

/**
 * SPAWN_PET 的 GMS083 事件（解码产物，版本词汇，byte/short 合法）。
 * decode 读序即协议序：客户端时间戳（服务端不消费）+ slot + 保留字节 + lead 位。
 */
public final class PetPacket {

    private PetPacket() {
    }

    public record SpawnPet(byte slot, boolean lead) {
    }

    public static SpawnPet decodeSpawnPet(InPacket p) {
        p.readInt();
        byte slot = p.readByte();
        p.readByte();
        boolean lead = p.readByte() == 1;
        return new SpawnPet(slot, lead);
    }
}
