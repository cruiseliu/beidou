package org.gms.client.character;

import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.config.GameConfig;
import org.gms.constants.id.ItemId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;
import org.gms.server.TimerManager;
import org.gms.util.I18nUtil;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * 装备域子模块（CharacterInventory 持有，关系对齐 CharacterBuffs↔ActiveBuffs）：
 * 已穿戴装备的数据 + 逻辑内聚——穿戴变化编排（equipChanged）、已装备查询、装备经验、
 * 发装备（gainEquip）、精灵吊坠计时（pendantExp 收编于此）。
 * 背包存储/槽位/拾取/出售等通用域留在 CharacterInventory；依赖经 owner 门面调用。
 */
class CharacterEquips {
    private final Character owner;

    /** 已穿戴背包引用（CharacterInventory 构造时传入——Character 的字段初始化顺序不保证先于本类） */
    private final InventoryTab equipped;

    CharacterEquips(Character owner, InventoryTab equipped) {
        this.owner = owner;
        this.equipped = equipped;
    }

    /** 已穿戴装备属性聚合（全量重算）：共享段 [0, EQUIP_INDEX_BEGIN) 逐属性求和，
     *  下标与 CharacterStats 的 base/total 数组对齐（同一 Stat 序）；装备独有段角色 total 不消费。 */
    int[] aggregateStatTotals() {
        int[] totals = new int[Stat.count()];
        Stat[] all = Stat.values();
        for (ItemSlot item : equipped) {
            Equip eq = item.getEquipInfo();
            for (int i = 0; i < Stat.EQUIP_INDEX_BEGIN; i++) {
                totals[i] += eq.getStat(all[i]);
            }
        }
        return totals;
    }

    boolean haveItemEquipped(int itemid) {
        return (equipped.findById(itemid) != null);
    }

    /** 装备更新：外观（可见装备）+ 属性源同时变化 */
    void equipChanged() {
        owner.appearanceChanged();
        owner.recalcStats();
    }

    private Collection<ItemSlot> getUpgradeableEquipList() {
        Collection<ItemSlot> fullList = equipped.list();
        if (GameConfig.getServerBoolean("use_equipment_level_up_cash")) {
            return fullList;
        }

        Collection<ItemSlot> eqpList = new LinkedHashSet<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (ItemSlot it : fullList) {
            if (!ii.isCash(it.getItemId())) {
                eqpList.add(it);
            }
        }

        return eqpList;
    }

    void increaseEquipExp(int expGain) {
        if (owner.allowExpGain) {     // thanks Vcoc for suggesting equip EXP gain conditionally
            if (expGain < 0) {
                expGain = Integer.MAX_VALUE;
            }

            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            for (ItemSlot item : getUpgradeableEquipList()) {
                Equip nEquip = item.getEquipInfo();
                String itemName = ii.getName(nEquip.getItemId());
                if (itemName == null) {
                    continue;
                }

                nEquip.gainItemExp(owner.client, expGain, item);
            }
        }
    }

    void showAllEquipFeatures() {
        StringBuilder showMsg = new StringBuilder();

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (ItemSlot item : equipped.list()) {
            Equip nEquip = item.getEquipInfo();
            String itemName = ii.getName(nEquip.getItemId());
            if (itemName == null) {
                continue;
            }

            showMsg.append(nEquip.showEquipFeatures(owner.client));
        }

        if (!showMsg.isEmpty()) {
            owner.showHint("#ePLAYER EQUIPMENTS:#n\r\n\r\n" + showMsg, 400);
        }
    }

    /**
     * 发装备：stats 按 Stat.ordinal() 对齐（null = 保持装备默认值，显式 0 = 设为 0），
     * upgradeSlot/expire 传 null 取默认；expire > 0 为分钟数，&le;0 永久。
     */
    void gainEquip(int itemId, Integer[] stats, Byte upgradeSlot, Long expire) {
        if (!ItemConstants.getInventoryType(itemId).equals(InventoryType.EQUIP)) {
            owner.message(I18nUtil.getMessage("AbstractPlayerInteraction.gainEquip.message1"));
            return;
        }
        ItemSlot equipSlot = ItemInformationProvider.getInstance().getEquipById(itemId);
        Equip equip = equipSlot.getEquipInfo();
        if (!InventoryManipulator.checkSpace(owner.getClient(), itemId, 1, equipSlot.getOwner())) {
            owner.message(I18nUtil.getMessage("AbstractPlayerInteraction.gainEquip.message2", InventoryType.EQUIP.getName()));
        }
        Stat[] all = Stat.values();
        for (int i = 0; i < stats.length && i < Stat.count(); i++) {
            if (stats[i] != null) {
                equip.setStat(all[i], stats[i]);
            }
        }
        if (upgradeSlot != null) {
            equip.setEnhancementSlots(upgradeSlot);
        }
        if (expire != null) {
            equipSlot.setExpiration(expire > 0 ? MINUTES.toMillis(expire) + System.currentTimeMillis() : -1);
        }
        InventoryManipulator.addFromDrop(owner.getClient(), equipSlot, false);
    }

}
