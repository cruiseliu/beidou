package org.gms.client.inventory;

import org.gms.client.character.Character;
import org.gms.remote.RemoteClientBatch;
import org.gms.remote.modules.inventory.server.SlotChange;
import org.gms.constants.inventory.ItemConstants;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Inventory 事务（2b 交换式：shadow rehearsal + swap + diff）：
 * begin 统一锁常规 tab（ordinal 序，持锁至 commit/rollback）并做影子薄包装（Item 本体共享）；
 * 流式 add/remove 族只改影子（不可行置 failed，后续 no-op）——不记录任何操作序列；
 * commit 把影子容器整体换装进真身，通知 = diff(换装前, 换装后) 一次发包。
 * testUpdate 模式不换装，仅返回可行性。
 *
 * <p>容器只关心状态不关心操作序列：瞬时中间态（加了又删、加了又并）在 diff 中自然抵消。
 * 撤销 = 丢弃影子（真身从未被碰——比"碰了再还原"更强的不变量）。
 *
 * <p>已登记的边界决策：
 * <ul>
 *   <li>charge 变更在语义层对 Inventory/本事务不可见（将来走独立的 UpdateAmmoCharge
 *       事件，翻译成背包 packet 是 v83 编码器的私事）；diff 身份 = Item 引用，
 *       引用不变则 charge 变更定义性地不产生通知。</li>
 *   <li>move 不复用本事务（将来背包排序走独立 move TX）；diff 规格因此不含 move。</li>
 *   <li>外部 ItemSlot 引用失效已审计清白（引用面均为游离对象/局部变量，2026-08-27）；
 *       Item 引用因共享纪律不失效。</li>
 * </ul>
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

    private final Inventory inventory;
    private final Character character;
    private final boolean testMode;
    /** roll 随机源（默认 ThreadLocalRandom；可注入供测试/审计） */
    private final DoubleSupplier random;

    /** 影子：type → 薄包装 tab（共享 Item 本体，仅槽位视角隔离；事务改影子不污染真实容器） */
    private final EnumMap<InventoryType, InventoryTab> shadow = new EnumMap<>(InventoryType.class);

    private boolean failed = false;

    /** P2 事务信封：prepare 起缓冲全部通知；成功随 commit 冲刷，failed → drop 弃段 */
    private RemoteClientBatch packetScope;

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
                InventoryTab copy = new InventoryTab(character, type, real.getSlotLimit(), true);   // 影子不派发钩子
                for (ItemSlot item : real.list()) {
                    copy.addItemFromDB(ItemSlot.shadowWrap(item));   // Item 共享：薄包装
                }
                shadow.put(type, copy);
            }
        } catch (RuntimeException e) {
            end();
            throw e;
        }
        packetScope = character.getRemote().batch();   // 事务信封：段从现在起收集一切通知
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

    public InventoryTransaction add(Item item, int quantity) {
        return add(new ItemStack(item, quantity));
    }

    public InventoryTransaction add(List<ItemStack> items) {
        for (ItemStack item : items) {
            add(item);
        }
        return this;
    }

    private void addInternal(ItemStack item) {
        int itemId = item.itemId;
        InventoryType type = tabTypeOf(itemId);
        ItemStack remaining = shadow.get(type).addInternal(item);
        if (remaining != null) {
            failed = true;
        }
    }

    public InventoryTransaction remove(ItemStack item) {
        if (!failed) {
            removeInternal(item, true);
        }
        return this;
    }

    public InventoryTransaction remove(Item item, int quantity) {
        return remove(new ItemStack(item, quantity));
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
     * 影子上自首堆逐个扣减；不足且 mayFail → failed。
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
            if (slot.quantity == 0) {
                tab.removeSlot(slot.position);   // 扣空的组整格移除
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
        for (ItemStackWeight candidate : pool.items) {
            if (!canFit(candidate.stack)) {
                failed = true;
                return;
            }
        }
        ItemStack picked = rollWeighted(pool);
        addInternal(picked);
    }

    private boolean canFit(ItemStack stack) {
        int itemId = stack.itemId;
        InventoryType type = tabTypeOf(itemId);
        InventoryTab tab = shadow.get(type);

        int stackLimit = stack.item.getStackLimit();

        int freeSlots = tab.getNumFreeSlot();
        if (freeSlots * stackLimit >= stack.quantity) {
            return true;
        }

        ItemStack remaining = stack.shallowCopy();
        remaining.takeAtMost(freeSlots * stackLimit);

        for (ItemSlot slot : tab.listById(itemId)) {
            if (!remaining.canMergeWith(slot.getItem())) {
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

    private ItemStack rollWeighted(ItemPool pool) {
        double total = 0;
        for (ItemStackWeight option : pool.items) {
            total += Math.max(0, option.weight);
        }
        double roll = random.getAsDouble() * total;
        for (ItemStackWeight option : pool.items) {
            roll -= Math.max(0, option.weight);
            if (roll < 0) {
                return option.stack;
            }
        }
        return pool.items.get(pool.items.size() - 1).stack;
    }

    // ── 提交 ──

    /** failed → 丢弃返回 false；test 模式返回可行性（不换装）；否则整容器换装 + diff 一次发包 */
    public boolean commit() {
        if (failed) {
            rollback();
            if (packetScope != null) {
                packetScope.drop();   // 失败兜底：整段事件弃置
            }
            return false;
        }

        List<SlotChange> changes = List.of();
        if (!testMode) {
            changes = new ArrayList<>();
            for (InventoryType type : TX_TYPES) {
                InventoryTab real = inventory.getTab(type);
                InventoryTab sh = shadow.get(type);
                var before = real.snapshot();
                real.adopt(sh);
                changes.addAll(InventoryTab.diff(before, sh.snapshot()));
            }
            if (!changes.isEmpty()) {
                character.getRemote().inventory().updateInventory(changes);
            }
        }
        if (packetScope != null) {
            packetScope.commit();
        }

        end();
        // 钩子事件经 diff 派发（adopt 不经过 addSlot/removeSlot）：Added/Removed 即"进入/离开背包"。
        // 必须在 end() 释放背包锁之后：钩子同步执行（状态迁移型，调用方须等待完成），可能阻塞
        // 调用方线程等 strand——持背包锁等待会让 strand 上的背包操作反向等锁，死锁配方（M1.5）。
        for (SlotChange change : changes) {
            if (change instanceof SlotChange.Added added) {
                added.item().onEnterInventory(character, false);
            } else if (change instanceof SlotChange.Removed removed) {
                removed.item().onLeaveInventory(character, false);
            }
        }
        return true;
    }

    private void rollback() {
        end();   // 丢弃影子即回滚（真身从未被碰——2b 更强不变量）
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
