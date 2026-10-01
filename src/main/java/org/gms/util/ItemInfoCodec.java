package org.gms.util;

import org.gms.client.pet.Pet;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.ItemSlot;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.packet.OutPacket;
import org.gms.constants.game.ExpTable;
import org.gms.server.ItemInformationProvider;
import org.gms.client.character.Stat;

/**
 * 物品条目的 wire 编码（INVENTORY_OPERATION 条目与 SET_FIELD inventory 段共用）。
 * 原 PacketCreator.addItemInfo/addExpirationTime 的独立提取——调用面：
 * PacketCreator（legacy 道具包）、WeddingPackets 与 SetFieldPacket（进图主包 inventory 段）。
 */
public final class ItemInfoCodec {

    private ItemInfoCodec() {
    }

    public static void addExpirationTime(final OutPacket p, long time) {
        p.writeLong(FieldTime.getTime(time)); // offset expiration time issue found thanks to Thora
    }

    public static void addItemInfo(final OutPacket p, ItemSlot item, boolean zeroPosition, Pet pet) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        boolean isCash = ii.isCash(item.getItemId());
        boolean isPet = item.getPetId() > -1;
        boolean isRing = false;
        Equip equip = null;
        short pos = (short) item.getPosition();
        byte itemType = (byte) item.getItemType();
        if (itemType == 1) {
            equip = item.getEquipInfo();
            isRing = equip.getRingId() > -1;
        }
        if (!zeroPosition) {
            if (equip != null) {
                if (pos < 0) {
                    pos *= -1;
                }
                p.writeShort(pos > 100 ? pos - 100 : pos);
            } else {
                p.writeByte(pos);
            }
        }
        p.writeByte(itemType);
        p.writeInt(item.getItemId());
        p.writeBool(isCash);
        if (isCash) {
            p.writeLong(isPet ? item.getPetId() : isRing ? equip.getRingId() : item.getCashInfo() != null ? item.getCashInfo().getCashId() : 0);
        }
        addExpirationTime(p, item.LEGACY_getExpiration());
        if (isPet) {
            p.writeFixedString(StringUtil.getRightPaddedStr(pet.getName(), '\0', 13));
            p.writeByte(pet.getLevel());
            p.writeShort((short) Math.min(pet.getTameness(), 30000));   // 宠物面板同款截断
            p.writeByte(pet.getFullness());
            // pet wire expiration 三分支（对齐 InventoryTranslator.petBody）：
            // 失活 → EXPIRED；-1（永久）→ PERMANENT；其他 → toWire
            long wireExpiration;
            if (!pet.isAlive()) {
                wireExpiration = FieldTime.EXPIRED;
            } else if (item.LEGACY_getExpiration() == -1) {
                wireExpiration = FieldTime.PERMANENT;
            } else {
                wireExpiration = FieldTime.getTime(item.LEGACY_getExpiration());
            }
            addExpirationTime(p, wireExpiration);
            p.writeShort(pet.getFlags()); // PetAttribute noticed by lrenex & Spoon
            p.writeShort(0); // PetSkill
            p.writeInt(18000); // RemainLife
            p.writeShort(0); // attribute
            return;
        }
        if (equip == null) {
            p.writeShort(item.getQuantity());
            p.writeString(item.getOwner());
            p.writeShort(item.getLegacyFlags()); // flag

            if (ItemConstants.isRechargeable(item.getItemId())) {
                p.writeInt(2);
                p.writeBytes(new byte[]{(byte) 0x54, 0, 0, (byte) 0x34});
            }
            return;
        }
        p.writeByte(equip.getEnhancementSlots()); // upgrade slots
        p.writeByte(equip.getEnhancementLevel()); // level
        p.writeShort(equip.getStat(Stat.STR)); // str
        p.writeShort(equip.getStat(Stat.DEX)); // dex
        p.writeShort(equip.getStat(Stat.INT)); // int
        p.writeShort(equip.getStat(Stat.LUK)); // luk
        p.writeShort(equip.getStat(Stat.MAX_HP)); // hp
        p.writeShort(equip.getStat(Stat.MAX_MP)); // mp
        p.writeShort(equip.getStat(Stat.P_ATK)); // watk
        p.writeShort(equip.getStat(Stat.M_ATK)); // matk
        p.writeShort(equip.getStat(Stat.P_DEF)); // wdef
        p.writeShort(equip.getStat(Stat.M_DEF)); // mdef
        p.writeShort(equip.getStat(Stat.ACCURACY)); // accuracy
        p.writeShort(equip.getStat(Stat.AVOIDABILITY)); // avoid
        p.writeShort(equip.getStat(Stat.HANDS)); // hands
        p.writeShort(equip.getStat(Stat.SPEED)); // speed
        p.writeShort(equip.getStat(Stat.JUMP)); // jump
        p.writeString(item.getOwner()); // owner name
        p.writeShort(item.getLegacyFlags()); //Item Flags

        if (isCash) {
            for (int i = 0; i < 10; i++) {
                p.writeByte(0x40);
            }
        } else {
            int itemLevel = equip.getItemLevel();

            long expNibble = (ExpTable.getExpNeededForLevel(ii.getEquipLevelReq(item.getItemId())) * equip.getItemExp());
            expNibble /= ExpTable.getEquipExpNeededForLevel(itemLevel);

            p.writeByte(0);
            p.writeByte(itemLevel); //Item Level
            p.writeInt((int) expNibble);
            p.writeInt(equip.getVicious()); //WTF NEXON ARE YOU SERIOUS?
            p.writeLong(0);
        }
        addExpirationTime(p, -2);
        p.writeInt(-1);

    }
}
