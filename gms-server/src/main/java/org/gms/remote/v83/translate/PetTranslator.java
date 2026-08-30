package org.gms.remote.v83.translate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.client.PacketStat;
import org.gms.client.character.Character;
import org.gms.client.pet.Pet;

import java.awt.Point;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 宠物域翻译：语义调用 → v83 整帧（含 opcode 头）。
 * 与 StatsTranslator 等缓冲式 Translator 不同，宠物封包多为即时发送的地图广播
 * （出现/消失/演出），无合并语义——各方法到达即编码返回，由 route 直接 send。
 * STAT_CHANGED(宠物位) 为多对一落点（PetModule 语义骑 stats 包型，同 doc/09 §5.2）。
 *
 * <p>编码来源：原 PacketCreator 宠物段迁入（分层要求：remote 层不依赖 PacketCreator）；
 * 字符串/坐标基元对齐 ByteBufOutPacket（charset 长度前缀串、short x/y）。
 */
public final class PetTranslator {
    private static final int OPC_SPAWN_PET = 0xA8;
    private static final int OPC_PET_NAMECHANGE = 0xAC;
    private static final int OPC_PET_EXCEPTION_LIST = 0xAD;
    private static final int OPC_PET_COMMAND = 0xAE;
    private static final int OPC_SHOW_FOREIGN_EFFECT = 0xC6;
    private static final int OPC_SHOW_ITEM_GAIN_INCHAT = 0xCE;
    private static final int OPC_STAT_CHANGED = 0x1F;

    private final Charset charset;

    public PetTranslator(Charset charset) {
        this.charset = charset;
    }

    /** SPAWN_PET：出现（addPetInfo）/消失（remove + hunger 位）；fh 仅出现分支消费 */
    public ByteBuf spawnPet(Character chr, Pet pet, boolean remove, boolean hunger, int fh) {
        byte petIndex = chr.getPetIndex(pet);
        ByteBuf out = frame(OPC_SPAWN_PET);
        writeIntLE(out, chr.getId());
        out.writeByte(petIndex);
        if (remove) {
            out.writeByte(0);
            out.writeBoolean(hunger);
        } else {
            writePetInfo(out, pet, fh, chr.hasPetNameTag(petIndex), chr.hasPetChatballoon(petIndex));
        }
        return out;
    }

    /** STAT_CHANGED（仅 PET 掩码位）：三槽位 petid 长整型，属性栏刷新 */
    public ByteBuf petStatUpdate(Character chr) {
        ByteBuf out = frame(OPC_STAT_CHANGED);
        out.writeByte(0);
        writeIntLE(out, PacketStat.PET.getValue());
        Pet[] pets = chr.getSummonSlots();
        for (int i = 0; i < 3; i++) {
            writeLongLE(out, pets[i] != null ? pets[i].getUniqueId() : 0L);
        }
        out.writeByte(0);
        return out;
    }

    /** SHOW_ITEM_GAIN_INCHAT(4)：本人升级演出 */
    public ByteBuf petLevelUpOwn(byte index) {
        ByteBuf out = frame(OPC_SHOW_ITEM_GAIN_INCHAT);
        out.writeByte(4);
        out.writeByte(0);
        out.writeByte(index);
        return out;
    }

    /** SHOW_FOREIGN_EFFECT(4)：全图升级演出 */
    public ByteBuf petLevelUpForeign(Character chr, byte index) {
        ByteBuf out = frame(OPC_SHOW_FOREIGN_EFFECT);
        writeIntLE(out, chr.getId());
        out.writeByte(4);
        out.writeByte(0);
        out.writeByte(index);
        return out;
    }

    /** PET_COMMAND(response=1)：喂食反馈 */
    public ByteBuf petFoodResponse(int cid, byte index, boolean success, boolean hasChatBalloon) {
        ByteBuf out = frame(OPC_PET_COMMAND);
        writeIntLE(out, cid);
        out.writeByte(index);
        out.writeByte(1);
        out.writeBoolean(success);
        out.writeBoolean(hasChatBalloon);
        return out;
    }

    /** PET_NAMECHANGE：改名（携带名字标签佩戴位） */
    public ByteBuf petNameChange(Character chr, String newName, byte slot) {
        ByteBuf out = frame(OPC_PET_NAMECHANGE);
        writeIntLE(out, chr.getId());
        out.writeByte(slot);
        writeString(out, newName);
        out.writeBoolean(chr.hasPetNameTag(slot));
        return out;
    }

    /** PET_EXCEPTION_LIST：拾取过滤列表 */
    public ByteBuf exclusionList(int cid, int petId, byte petIdx, List<Integer> itemIds) {
        ByteBuf out = frame(OPC_PET_EXCEPTION_LIST);
        writeIntLE(out, cid);
        out.writeByte(petIdx);
        writeLongLE(out, petId);
        out.writeByte(itemIds.size());
        for (int id : itemIds) {
            writeIntLE(out, id);
        }
        return out;
    }

    // ── 字段编码（对齐 PacketCreator.addPetInfo / ByteBufOutPacket 基元）──

    private void writePetInfo(ByteBuf out, Pet pet, int fh, boolean hasNameTag, boolean hasChatBalloon) {
        out.writeByte(1);
        out.writeByte(0);   // showpet 位（v83 固定 0）
        writeIntLE(out, pet.getItemId());
        writeString(out, pet.getName());
        writeLongLE(out, pet.getUniqueId());
        Point pos = pet.getPos();
        out.writeShortLE((short) pos.getX());
        out.writeShortLE((short) pos.getY());
        out.writeByte(pet.getStance());
        out.writeShortLE(fh);
        out.writeBoolean(hasNameTag);
        out.writeBoolean(hasChatBalloon);
    }

    private static ByteBuf frame(int opcode) {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE((short) opcode);
        return out;
    }

    private void writeString(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(charset);
        out.writeShortLE(bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeIntLE(ByteBuf out, int value) {
        out.writeIntLE(value);
    }

    private static void writeLongLE(ByteBuf out, long value) {
        out.writeLongLE(value);
    }
}
