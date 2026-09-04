package org.gms.remote.v83.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * SPAWN_PET（0xA8）封包树：出现（addPetInfo）/消失（remove + hunger 位）两形态。
 * 名字以会话编码字节传入（冻结纪律：packet 层不做字符集转换）。
 */
public record SpawnPetPacket(int cid, byte petIndex, PetBody body) {
    private static final int OPCODE = 0xA8;

    public static SpawnPetPacket remove(int cid, byte petIndex, boolean hunger) {
        return new SpawnPetPacket(cid, petIndex, new PetBody.Remove(hunger));
    }

    public static SpawnPetPacket appear(int cid, byte petIndex, int itemId, byte[] name,
                                        long petId, short x, short y, byte stance, short fh,
                                        boolean hasNameTag, boolean hasChatBalloon) {
        return new SpawnPetPacket(cid, petIndex,
                new PetBody.Appear(itemId, name, petId, x, y, stance, fh, hasNameTag, hasChatBalloon));
    }

    public static ByteBuf encode(SpawnPetPacket packet) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(OPCODE);
        out.writeIntLE(packet.cid());
        out.writeByte(packet.petIndex());
        switch (packet.body()) {
            case PetBody.Remove(boolean hunger) -> {
                out.writeByte(0);
                out.writeBoolean(hunger);
            }
            case PetBody.Appear(int itemId, byte[] name, long petId, short x, short y,
                        byte stance, short fh, boolean hasNameTag, boolean hasChatBalloon) -> {
                out.writeByte(1);
                out.writeByte(0);   // showpet 位（v83 固定 0）
                out.writeIntLE(itemId);
                out.writeShortLE(name.length);
                out.writeBytes(name);
                out.writeLongLE(petId);
                out.writeShortLE(x);
                out.writeShortLE(y);
                out.writeByte(stance);
                out.writeShortLE(fh);
                out.writeBoolean(hasNameTag);
                out.writeBoolean(hasChatBalloon);
            }
        }
        return out;
    }

    public sealed interface PetBody {
        record Remove(boolean hunger) implements PetBody {
        }

        record Appear(int itemId, byte[] name, long petId, short x, short y, byte stance,
                      short fh, boolean hasNameTag, boolean hasChatBalloon) implements PetBody {
        }
    }
}
