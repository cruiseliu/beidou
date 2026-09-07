package org.gms.remote.v83.in.packet;

import org.gms.net.packet.InPacket;

/**
 * 宠物族收包的解码产物（纯 record，只携带包内字段）。
 * 解码只读包：目标宠物等角色状态由 gameplay 侧按自己的状态选择/校验（no-peek）。
 */
public final class PetPacket {

    private PetPacket() {
    }

    /** SPAWN_PET：召唤/下阵意图（slot = CASH 背包槽位，lead = 是否作为头宠） */
    public record SpawnPet(byte slot, boolean lead) {
    }

    /** PET_FOOD：使用道具意图（slot = USE 背包槽位，itemId = 声明的道具 id） */
    public record PetFood(short slot, int itemId) {
    }

    /** SPAWN_PET 解码：客户端时间戳（首 int，服务端不消费）+ slot + 保留字节 + lead 位 */
    public static SpawnPet decodeSpawnPet(InPacket p) {
        p.readInt();
        byte slot = p.readByte();
        p.readByte();
        boolean lead = p.readByte() == 1;
        return new SpawnPet(slot, lead);
    }

    /** PET_FOOD 解码：客户端时间戳（首 int，服务端不消费）+ USE 槽位 + itemId */
    public static PetFood decodePetFood(InPacket p) {
        p.readInt();
        short slot = p.readShort();
        int itemId = p.readInt();
        return new PetFood(slot, itemId);
    }
}
