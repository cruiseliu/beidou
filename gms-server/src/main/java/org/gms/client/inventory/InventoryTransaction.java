package org.gms.client.inventory;

import org.gms.client.character.Character;
import org.gms.constants.inventory.ItemConstants;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Inventory 事务（shadow rehearsal，设计见 doc/09）：
 * begin 统一锁常规 tab（ordinal 序，持锁至 commit/rollback）并深拷贝影子；
 * 流式 add/remove 族在影子上预演并记录槽级 op（不可行置 failed，后续 no-op）；
 * commit 逐条重放 op 到真实 tab（持锁保证与影子逐槽一致），ModifyInventory 一次发包。
 * testUpdate 模式 commit 不重放，仅返回可行性。
 *
 * <p>约束：EQUIPPED/CANHOLD 不支持（UnsupportedOperationException）；影子单独存本类不占
 * tabs[CAN_HOLD]；事务不得跨线程。addPool 经 addPoolAndCommit 组合暴露：验全（roll 样本空间
 * 冻结为 pool 全体，反操纵）→ 按权重 roll → 影子落位 → commit。
 */
public class InventoryTransaction {
    /** 事务域：EQUIPPED 与 CANHOLD 不在其中 */
    private static final InventoryType[] TX_TYPES = {
        InventoryType.EQUIP,
        InventoryType.USE,
        InventoryType.SETUP,
        InventoryType.ETC,
        InventoryType.CASH,
    };

    /** 槽级重放动作（影子操作时记录，commit 对真实 tab 逐条执行；影子与真实起点同构，结果确定） */
    private abstract static class SlotOp {
        final InventoryType type;
        final int slot;

        SlotOp(InventoryType type, int slot) {
            this.type = type;
            this.slot = slot;
        }

        abstract void replay(InventoryTab real, List<ModifyInventory> mods);
    }

    /** 新增槽：影子新建的 ItemSlot 对象直接落真实同槽（该对象此后即为真实对象） */
    private static final class AddSlotOp extends SlotOp {
        private final ItemSlot item;

        AddSlotOp(InventoryType type, int slot, ItemSlot item) {
            super(type, slot);
            this.item = item;
        }

        @Override
        void replay(InventoryTab real, List<ModifyInventory> mods) {
            real.addItemFromDB(item);
            mods.add(new ModifyInventory(0, item));
        }
    }

    /** 堆数量变更（并堆/扣减后仍留）：以影子终值作用于真实原对象 */
    private static final class SetQtyOp extends SlotOp {
        private final int newQty;
        private final boolean removed;   // 扣减至 0 整槽移除

        SetQtyOp(InventoryType type, int slot, int newQty, boolean removed) {
            super(type, slot);
            this.newQty = newQty;
            this.removed = removed;
        }

        @Override
        void replay(InventoryTab real, List<ModifyInventory> mods) {
            ItemSlot realItem = real.getItem(slot);
            if (realItem == null) {
                return;
            }
            if (removed) {
                real.removeSlot(slot);
                mods.add(new ModifyInventory(3, realItem));
            } else {
                realItem.setQuantity(Math.max(0, newQty));
                mods.add(new ModifyInventory(1, realItem));
            }
        }
    }

    private final Inventory inventory;
    private final Character character;
    private final boolean testMode;
    /** roll 随机源（默认 ThreadLocalRandom；可注入供测试/审计） */
    private final DoubleSupplier random;

    /** 影子：type → 深拷贝 tab（现有堆为 ItemSlot.copy，事务改影子不污染真实对象） */
    private final EnumMap<InventoryType, InventoryTab> shadow = new EnumMap<>(InventoryType.class);
    /** 槽级 op log（发生序） */
    private final List<SlotOp> ops = new ArrayList<>();

    private boolean failed = false;

    InventoryTransaction(Inventory inventory, Character character, boolean testMode) {
        this.inventory = inventory;
        this.character = character;
        this.testMode = testMode;
        this.random = () -> ThreadLocalRandom.current().nextDouble();
        begin();
    }

    private void begin() {
        // 先统一锁（ordinal 序），再拷贝——持锁后无并发窗口，影子与真实一致
        for (InventoryType type : TX_TYPES) {
            inventory.getTab(type).lockInventory();
        }
        try {
            for (InventoryType type : TX_TYPES) {
                InventoryTab real = inventory.getTab(type);
                InventoryTab copy = new InventoryTab(character, type, real.getSlotLimit());  // todo: [refactor] use "more-raw" tab
                for (ItemSlot item : real.list()) {
                    copy.addItemFromDB(item.copy());
                }
                shadow.put(type, copy);
            }
        } catch (RuntimeException e) {
            end();
            throw e;
        }
    }

    private void end() {
        for (int i = TX_TYPES.length - 1; i >= 0; i--) {
            inventory.getTab(TX_TYPES[i]).unlockInventory();
        }
    }

    // ── 流式操作（failed/finished 后 no-op 返回 this） ──

    public InventoryTransaction add(ItemStack item) {
        if (!failed) {
            addInternal(item);
        }
        return this;
    }

    public InventoryTransaction add(List<ItemStack> items) {
        for (ItemStack item : items) {
            add(item);
        }
        return this;
    }

    private void addInternal(ItemStack item) {
        // fixme: [refactor] check exclusive item
        ItemStack remaining = item.copy();

        int itemId = remaining.itemId;
        InventoryType type = tabTypeOf(itemId);
        InventoryTab tab = shadow.get(type);

        // 可充值与装备同为"一组一格"（quantity = 组数、次数在该组宿主 Item 上；分组永久）：
        // 装备显式排除（其 wz slotMax 无堆叠意义），可充值由 getStackLimit 归一为 1——
        // 上限 1 即天然不进并堆循环、每个 quantity 单位落一个新槽
        boolean stackable = !ItemConstants.isEquipment(itemId);
        int stackLimit = stackable ? item.getStackLimit(character.getClient()) : 1;

        if (stackLimit > 1) {
            for (ItemSlot slot : tab.listById(itemId)) {
                if (remaining.quantity == 0) {
                    return;
                }
                if (!slot.getOwner().isEmpty() || slot.getFlag() != 0) {  // fixme: [refactor] merge same flag
                    continue;
                }
                if (slot.quantity < stackLimit) {
                    slot.quantity += remaining.takeAtMost(stackLimit - slot.quantity).quantity;
                    ops.add(new SetQtyOp(type, slot.position, slot.quantity, false));
                }
            }
        }

        while (remaining.quantity > 0) {
            ItemSlot slot = tab.addStack(remaining.takeAtMost(stackLimit));
            if (slot == null) {
                failed = true;
                return;
            }
            ops.add(new AddSlotOp(type, slot.position, slot));
        }
    }

    public InventoryTransaction remove(ItemStack item) {
        if (!failed) {
            removeInternal(item, true);
        }
        return this;
    }

    public InventoryTransaction remove(List<ItemStack> items) {
        for (ItemStack item : items) {
            remove(item);
        }
        return this;
    }

    public InventoryTransaction removeAtMost(ItemStack item) {
        if (!failed) {
            removeInternal(item, false);
        }
        return this;
    }

    public InventoryTransaction removeAtMost(List<ItemStack> items) {
        for (ItemStack item : items) {
            removeAtMost(item);
        }
        return this;
    }

    /**
     * 影子上自首堆逐个扣减（与 op 记录序一致）；不足且 mayFail → failed。
     * 可充值的 quantity 语义为组数（每组一格、每组算 1）；发数级扣减（消耗一发）走消耗路径，不在此。
     */
    private void removeInternal(ItemStack item, boolean mayFail) {
        int itemId = item.itemId;
        InventoryType type = tabTypeOf(itemId);
        InventoryTab tab = shadow.get(type);

        int remaining = item.quantity;

        for (ItemSlot slot : tab.listById(itemId)) {
            if (remaining == 0) {
                return;
            }
            remaining -= slot.takeAtMost(remaining).quantity;
            ops.add(new SetQtyOp(type, slot.position, slot.quantity, slot.quantity == 0));
            if (slot.quantity == 0) {
                tab.removeSlot(slot.position);   // 扣空的组整格移除，影子表项与 ops 保持一致
            }
        }

        if (mayFail && remaining > 0) {
            failed = true;
        }
    }

    // ── pool 收尾组合（唯一 pool 入口；验全冻结 roll 样本空间） ──

    /**
     * 两阶段：验全（影子只读试放，任一选项不可行 → failed）→ 按权重 roll 一个选项 →
     * 影子落位 → commit。验全在 roll 前：样本空间 = pool 全体，与背包状态无关（反操纵约束）。
     */
    public boolean addPoolAndCommit(ItemPool pool) {
        addPool(pool);
        return commit();
    }

    private void addPool(ItemPool pool) {
        if (failed) {
            return;
        }
        for (ItemStackWeight item : pool.items) {
            if (!canFit(item)) {
                failed = true;
                return;
            }
        }
        ItemStackWeight picked = rollWeighted(pool);
        addInternal(new ItemStack(picked.itemId, picked.quantity));
    }

    private boolean canFit(ItemStackWeight option) {
        int itemId = option.itemId;
        InventoryType type = tabTypeOf(itemId);
        InventoryTab tab = shadow.get(type);

        boolean stackable = !ItemConstants.isEquipment(itemId);
        int stackLimit = stackable
                ? new ItemStack(option.itemId, option.quantity).getStackLimit(character.getClient())
                : 1;

        int freeSlots = tab.getNumFreeSlot();
        if (freeSlots * stackLimit >= option.quantity) {
            return true;
        }

        ItemStack remaining = new ItemStack(itemId, option.quantity - freeSlots * stackLimit);
        for (ItemSlot slot : tab.listById(itemId)) {
            if (!slot.getOwner().isEmpty() || slot.getFlag() != 0) {  // fixme: [refactor] merge same flag
                continue;
            }
            if (slot.quantity < stackLimit) {
                remaining.takeAtMost(stackLimit - slot.quantity);
                if (remaining.quantity == 0) {
                    return true;
                }
            }
        }
        return remaining.quantity == 0;
    }

    private ItemStackWeight rollWeighted(ItemPool pool) {
        double total = 0;
        for (ItemStackWeight option : pool.items) {
            total += Math.max(0, option.weight);
        }
        double roll = random.getAsDouble() * total;
        for (ItemStackWeight option : pool.items) {
            roll -= Math.max(0, option.weight);
            if (roll < 0) {
                return option;
            }
        }
        return pool.items.get(pool.items.size() - 1);
    }

    // ── 提交 ──

    /** failed → 丢弃返回 false；test 模式返回可行性；否则逐条重放 + 一次发包 */
    public boolean commit() {
        if (failed) {
            rollback();
            return false;
        }

        if (!testMode) {
            List<ModifyInventory> mods = new ArrayList<>();
            for (SlotOp op : ops) {
                op.replay(inventory.getTab(op.type), mods);
            }
            if (!mods.isEmpty()) {
                // 经 remote 层发包：可充值物品 wire 前由 ModifyInventoryOp 做 charge→quantity 还原
                character.getRemote().updateInventory(mods);
            }
        }

        end();
        return true;
    }

    private void rollback() {
        end();   // 影子丢弃即回滚（真实从未被碰）
    }

    // ── 工具 ──

    private InventoryType tabTypeOf(int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        if (type == InventoryType.EQUIPPED || type == InventoryType.CANHOLD || type == InventoryType.UNDEFINED) {
            throw new UnsupportedOperationException("事务不支持该背包类型: " + type + " (itemId=" + itemId + ")");
        }
        return type;
    }
}
