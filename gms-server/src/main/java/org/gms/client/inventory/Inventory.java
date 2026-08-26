package org.gms.client.inventory;

import org.gms.client.character.Character;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色的背包集合：按 InventoryType 索引的全部 InventoryTab（含 CANHOLD 证明背包）。
 * 自 CharacterInventory 的 inventories 数组提取而成（重构期字段 public，
 * 方法随后逐步自 CharacterInventory 迁入）。
 */
public class Inventory {
    /** 各类型背包（下标 = InventoryType.ordinal()）；重构期 public，后续收紧 */
    public final InventoryTab[] tabs;
    private final Character owner;

    public Inventory(Character owner) {
        this.owner = owner;
        tabs = new InventoryTab[InventoryType.values().length];
        for (InventoryType type : InventoryType.values()) {
            byte b = 24;
            if (type == InventoryType.CASH) {
                b = 96;
            }
            tabs[type.ordinal()] = new InventoryTab(owner, type, b);
        }
        tabs[InventoryType.CANHOLD.ordinal()] = new InventoryProof(owner);
    }

    /** 开启提交模式事务：流式 add/remove 后 commit 落真（failed 自动回滚） */
    public InventoryTransaction tryUpdate() {
        return new InventoryTransaction(this, owner, false);
    }

    /** 开启试算模式事务：commit 不落真，仅返回可行性 */
    public InventoryTransaction testUpdate() {
        return new InventoryTransaction(this, owner, true);
    }

    public InventoryTab getTab(InventoryType type) {
        return tabs[type.ordinal()];
    }

    /**
     * 按 ItemStack 入包（参照 InventoryManipulator.addByIdInternal 的简化版：无 owner/flag/pet/sandbox）。
     * 入参即最终槽位内容：装备/可充值 quantity 语义见下，可堆叠物先并现有无主无旗标堆、
     * 溢出开新槽（slotMax 上限）；可充值（飞镖/子弹）已按组拆好——quantity = 组数、
     * 次数在该组宿主 Item 上（未指定则落位取 wz 满组），一组一格永不并堆；装备 quantity 恒 1。
     * 封包经 remote 层（modifyInventory 一包；有剩余放不下时附 SHOW_STATUS_INFO(0xff)
     * 满包提示）。参数不可变（不修改调用方的 ItemStack）。
     *
     * @return null = 全部放入；否则返回剩余量的 ItemStack（itemId 同参，
     *         quantity = 未放入量（可充值以组计）；完全失败时即原量）
     */
    public ItemStack add(ItemStack stack) {
        List<ModifyInventory> mods = new ArrayList<>();
        MutableOutcome outcome = new MutableOutcome();
        outcome.remaining = stack.quantity;
        addCollecting(stack, outcome, mods);
        if (outcome.full) {
            owner.getRemote().announceInventoryFull();
        }
        if (!mods.isEmpty()) {
            owner.getRemote().updateInventory(mods);
        }
        return outcome.remaining == 0 ? null : new ItemStack(stack.itemId, outcome.remaining);
    }

    /** 内部收集用可变载体（避免修改入参） */
    private static final class MutableOutcome {
        int remaining;
        boolean full;
        Item firstPlaced;
    }

    private void addCollecting(ItemStack stack, MutableOutcome outcome, List<ModifyInventory> mods) {
        int itemId = stack.itemId;
        InventoryType type = ItemConstants.getInventoryType(itemId);
        if (type == InventoryType.EQUIPPED || type == InventoryType.CANHOLD || type == InventoryType.UNDEFINED) {
            throw new UnsupportedOperationException("add 不支持该背包类型: " + type + " (itemId=" + itemId + ")");
        }
        InventoryTab tab = tabs[type.ordinal()];

        if (type == InventoryType.EQUIP) {
            if (outcome.remaining != 1) {
                throw new IllegalArgumentException("装备数量恒为 1: " + itemId + " x" + outcome.remaining);
            }
            ItemSlot equip = ItemInformationProvider.getInstance().getEquipById(itemId);
            if (tab.addItem(equip) == -1) {
                outcome.full = true;
                return;
            }
            mods.add(new ModifyInventory(0, equip));
            outcome.remaining = 0;
            outcome.firstPlaced = equip.getItem();
            return;
        }

        ItemStack remaining = stack.copy();   // 游标在副本上消耗，不碰调用方参数

        // 堆叠上限统一问 stack：可充值恒 1（一组一格——入参已按组拆好，quantity = 组数、
        // 次数在该组宿主 Item 上），其余查 wz slotMax。分组永久：上限 1 即天然不进并堆循环、逐单位开新槽。
        int stackLimit = stack.getStackLimit(owner.getClient());
        if (stackLimit > 1) {
            // 先并现有无主无旗标堆（与 addByIdInternal 的合并条件对齐）
            for (ItemSlot existing : tab.listById(itemId)) {
                if (remaining.quantity <= 0) {
                    return;
                }
                if (!existing.getOwner().isEmpty() || existing.getFlag() != 0) {
                    continue;
                }
                if (existing.getQuantity() < stackLimit) {
                    int placed = Math.min(stackLimit - existing.getQuantity(), remaining.quantity);
                    existing.setQuantity(existing.getQuantity() + placed);
                    remaining.quantity -= placed;
                    outcome.remaining = remaining.quantity;
                    mods.add(new ModifyInventory(1, existing));
                    if (outcome.firstPlaced == null) {
                        outcome.firstPlaced = existing.getItem();
                    }
                }
            }
        }
        // 溢出开新槽（每组最多 stackLimit；可充值单组占一格，次数由 fromStack 自宿主 Item 还原，
        // 无宿主时取 wz 满组）
        while (remaining.quantity > 0) {
            ItemSlot newItem = ItemSlot.fromStack(remaining.takeAtMost(stackLimit), 0);
            if (tab.addItem(newItem) == -1) {
                outcome.full = true;
                outcome.remaining = remaining.quantity;
                return;   // 部分成功：剩余量经返回值交给调用方决策（丢弃/补偿/落地）
            }
            outcome.remaining = remaining.quantity;
            mods.add(new ModifyInventory(0, newItem));
            if (outcome.firstPlaced == null) {
                outcome.firstPlaced = newItem.getItem();
            }
        }
    }

    /** 释放全部背包（防内存泄漏） */
    public void disposeAll() {
        for (InventoryTab tab : tabs) {
            if (tab != null) {
                tab.dispose();
            }
        }
    }
}
