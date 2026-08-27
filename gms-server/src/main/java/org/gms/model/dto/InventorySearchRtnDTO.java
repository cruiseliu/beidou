package org.gms.model.dto;

import com.mybatisflex.annotation.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.gms.client.character.Stat;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.ItemSlot;

import java.util.Optional;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InventorySearchRtnDTO {
    /**
     * 自增id，对应inventoryitemid
     */
    private Long id;
    /**
     * 角色id
     */
    private Integer characterId;
    /**
     * 物品id，对应itemid
     */
    private Integer itemId;
    /**
     * 物品类型，对应type
     * @see org.gms.client.inventory.ItemFactory
     */
    private Integer itemType;
    /**
     * 背包栏类型，对应inventorytype
     * @see org.gms.client.inventory.InventoryType
     */
    private Byte inventoryType;
    /**
     * 物品位置，对应position
     */
    private Short position;
    /**
     * 物品数量，对应quantity
     */
    private Short quantity;
    /**
     * 制作者，对应owner
     */
    private String owner;
    /**
     * 宠物id，对应petid
     */
    private Integer petId;
    /**
     * 物品标记，对应flag
     */
    private Short flag;
    /**
     * 物品有效期，对应expiration
     */
    private Long expiration;
    /**
     * 送礼人，对应giftFrom
     */
    private String giftFrom;
    /**
     * 是否在线
     */
    private boolean online;
    /**
     * 是否装备，判断子表inventoryequipment是否有数据
     */
    private boolean equipment;
    /**
     * 装备信息，equipment为true时有值
     */
    private InventoryEquipRtnDTO inventoryEquipment;

    /**
     *
     * 物品名称，根据itemID返回。
     */
    private String itemName;

    public ItemSlot toItem() {
        ItemSlot item;
        if (isEquipment()) {
            ItemSlot equipSlot = ItemSlot.equipItem(getItemId(), getPosition());
            Equip equip = equipSlot.getEquipInfo();
            equip.setEnhancementSlots(Optional.ofNullable(getInventoryEquipment().getUpgradeSlots()).orElse((byte) 0));
            equip.setEnhancementLevel(Optional.ofNullable(getInventoryEquipment().getLevel()).orElse((byte) 0));
            equip.setStat(Stat.STR, Optional.ofNullable(getInventoryEquipment().getAttStr()).orElse((short) 0));
            equip.setStat(Stat.DEX, Optional.ofNullable(getInventoryEquipment().getAttDex()).orElse((short) 0));
            equip.setStat(Stat.INT, Optional.ofNullable(getInventoryEquipment().getAttInt()).orElse((short) 0));
            equip.setStat(Stat.LUK, Optional.ofNullable(getInventoryEquipment().getAttLuk()).orElse((short) 0));
            equip.setStat(Stat.MAX_HP, Optional.ofNullable(getInventoryEquipment().getHp()).orElse((short) 0));
            equip.setStat(Stat.MAX_MP, Optional.ofNullable(getInventoryEquipment().getMp()).orElse((short) 0));
            equip.setStat(Stat.P_ATK, Optional.ofNullable(getInventoryEquipment().getPAtk()).orElse((short) 0));
            equip.setStat(Stat.M_ATK, Optional.ofNullable(getInventoryEquipment().getMAtk()).orElse((short) 0));
            equip.setStat(Stat.P_DEF, Optional.ofNullable(getInventoryEquipment().getPDef()).orElse((short) 0));
            equip.setStat(Stat.M_DEF, Optional.ofNullable(getInventoryEquipment().getMDef()).orElse((short) 0));
            equip.setStat(Stat.ACCURACY, Optional.ofNullable(getInventoryEquipment().getAcc()).orElse((short) 0));
            equip.setStat(Stat.AVOIDABILITY, Optional.ofNullable(getInventoryEquipment().getAvoid()).orElse((short) 0));
            equip.setStat(Stat.HANDS, Optional.ofNullable(getInventoryEquipment().getHands()).orElse((short) 0));
            equip.setStat(Stat.SPEED, Optional.ofNullable(getInventoryEquipment().getSpeed()).orElse((short) 0));
            equip.setStat(Stat.JUMP, Optional.ofNullable(getInventoryEquipment().getJump()).orElse((short) 0));
            equip.setVicious(Optional.ofNullable(getInventoryEquipment().getVicious()).orElse((short) 0));
            equip.setItemLevel(Optional.ofNullable(getInventoryEquipment().getItemLevel()).orElse((byte) 0));
            equip.setItemExp(Optional.ofNullable(getInventoryEquipment().getItemExp()).orElse(0));
            equip.setRingId(Optional.ofNullable(getInventoryEquipment().getRingId()).orElse(0));
            item = equipSlot;
        } else {
            item = new ItemSlot(getItemId(), getPosition(), getQuantity(), getPetId());
        }
        item.setOwner(getOwner());
        item.setExpiration(getExpiration());
        if (getGiftFrom() != null && !getGiftFrom().isEmpty() && item.getCashInfo() != null) {
            item.getCashInfo().setGiftFrom(getGiftFrom());
        }
        if (getFlag() != null) {
            item.getItem().setFlagsFromLegacy(getFlag());
        }
        return item;
    }
}
