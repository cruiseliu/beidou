package org.gms.client.inventory;

import org.gms.client.character.Character;
import org.gms.constants.inventory.ItemConstants;
import org.gms.remote.SlotChange;

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
        InventoryTab tab = getTab(ItemConstants.getInventoryType(stack.itemId));

        List<SlotChange> changes;
        ItemStack remaining;
        // 快照→变更→快照→通知 必须在锁内完成：通知与真身变更同序，防止
        // "后改的槽先发、先改的槽后发"把客户端绝对值倒推回旧值（Q1 竞态窗口）。
        // ReentrantLock 可重入，TX 外层持锁时不阻塞；先例：legacy addById 与 TX commit 均锁内发包。
        try (var ignored = org.gms.util.Locks.acquire(tab.lock)) {
            var before = tab.snapshot();
            remaining = tab.addInternal(stack);
            changes = InventoryTab.diff(before, tab.snapshot());

            if (remaining != null) {
                owner.getRemote().inventory().announceInventoryFull();
            }
            if (!changes.isEmpty()) {
                owner.getRemote().inventory().updateInventory(changes);
            }
        }
        return remaining;
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
