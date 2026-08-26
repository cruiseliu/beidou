package org.gms.client.inventory;

import org.gms.client.character.Character;

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

    /** 释放全部背包（防内存泄漏） */
    public void disposeAll() {
        for (InventoryTab tab : tabs) {
            if (tab != null) {
                tab.dispose();
            }
        }
    }
}
