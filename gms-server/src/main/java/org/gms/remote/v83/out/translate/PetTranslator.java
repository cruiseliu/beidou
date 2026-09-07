package org.gms.remote.v83.out.translate;

import org.gms.client.character.Character;
import org.gms.client.pet.Pet;
import org.gms.remote.v83.out.packet.PetExceptionListPacket;
import org.gms.remote.v83.out.packet.PetFoodResponsePacket;
import org.gms.remote.v83.out.packet.PetNameChangePacket;
import org.gms.remote.v83.out.packet.ShowForeignEffectPacket;
import org.gms.remote.v83.out.packet.ShowItemGainInchatPacket;
import org.gms.remote.v83.out.packet.SpawnPetPacket;
import org.gms.remote.v83.out.packet.StatChangedPacket;

import java.awt.Point;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 宠物域翻译：语义调用 → packet record（编码与日志归 route 层）。
 * 多为即时发送的地图广播（出现/消失/演出），无合并语义；
 * STAT_CHANGED(宠物位) 为多对一落点（PetModule 语义骑 stats 包型）。
 * 职责边界见 gms-server/doc/package-client.md §3/§6。
 */
public final class PetTranslator {
    private final Charset charset;

    public PetTranslator(Charset charset) {
        this.charset = charset;
    }

    /** SPAWN_PET：出现（addPetInfo）/消失（remove + hunger 位）；fh 仅出现分支消费 */
    public SpawnPetPacket spawnPet(Character chr, Pet pet, boolean remove, boolean hunger, int fh) {
        byte petIndex = chr.getPetIndex(pet);
        if (remove) {
            return SpawnPetPacket.remove(chr.getId(), petIndex, hunger);
        }
        Point pos = pet.getPos();
        return SpawnPetPacket.appear(chr.getId(), petIndex,
                pet.getItemId(), pet.getName().getBytes(charset), pet.getPetId(),
                (short) pos.getX(), (short) pos.getY(), (byte) pet.getStance(),
                (short) fh, chr.hasPetNameTag(petIndex), chr.hasPetChatballoon(petIndex));
    }

    /** STAT_CHANGED（仅 PET 掩码位）：三槽位 petid，属性栏（槽位指派）刷新 */
    public StatChangedPacket petStatUpdate(Character chr) {
        Pet[] pets = chr.LEGACY_getSummonSlots();
        return StatChangedPacket.petIds(false, new long[]{
                pets[0] != null ? pets[0].getPetId() : 0L,
                pets[1] != null ? pets[1].getPetId() : 0L,
                pets[2] != null ? pets[2].getPetId() : 0L});
    }

    /** 升级演出（本人帧 SHOW_ITEM_GAIN_INCHAT / 全图帧 SHOW_FOREIGN_EFFECT） */
    public ShowItemGainInchatPacket petLevelUpOwn(int index) {
        return new ShowItemGainInchatPacket((byte) index);
    }

    public ShowForeignEffectPacket petLevelUpForeign(Character chr, int index) {
        return new ShowForeignEffectPacket(chr.getId(), (byte) index);
    }

    /** 喂食反馈（全图气球） */
    public PetFoodResponsePacket petFoodResponse(Character chr, int slot, boolean success, boolean hasChatBalloon) {
        return new PetFoodResponsePacket(chr.getId(), (byte) slot, success, hasChatBalloon);
    }

    /** 改名演出（全图） */
    public PetNameChangePacket petNameChange(Character chr, String newName, int slot) {
        return new PetNameChangePacket(chr.getId(), (byte) slot,
                newName.getBytes(charset), chr.hasPetNameTag(slot));
    }

    /** 拾取过滤列表下发（本人） */
    public PetExceptionListPacket ignoreList(int cid, byte petIndex, long petId, List<Integer> itemIds) {
        return new PetExceptionListPacket(cid, petIndex, petId, itemIds);
    }
}
