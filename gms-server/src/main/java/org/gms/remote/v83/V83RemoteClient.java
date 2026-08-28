package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.remote.BasicModule;
import org.gms.remote.BasicUpdate;
import org.gms.remote.CooldownModule;
import org.gms.remote.InventoryModule;
import org.gms.remote.RemoteClient;
import org.gms.remote.RemoteUpdate;
import org.gms.remote.ScopeLog;
import org.gms.remote.SemanticEvent;
import org.gms.remote.SkillUpdate;
import org.gms.remote.SkillsModule;
import org.gms.remote.SpUpdate;
import org.gms.remote.StatsModule;
import org.gms.remote.SlotChange;
import org.gms.remote.StatsUpdate;

import java.util.List;

/**
 * RemoteClient 的 v83 实现：内置有状态合并域（见 RemoteClient 类注释）。
 * 本类负责事务作用域（P2：可丢弃、按段记录事件延迟处理）与语义调用到 opcode 编码器的**路由**——
 * 每个 opcode 一个编码器（{@link StatChangedOp}/{@link UpdateSkillsOp}/{@link CooldownOp}），
 * update→op 为多对多（如 stats/sp/basic 域合并进同一 STAT_CHANGED；removeSkill 复用 UPDATE_SKILLS）。
 * 未开域时语义调用立即发送；开域期间入队，最外层域关闭时按固定顺序逐 op 编码发送。
 */
public final class V83RemoteClient implements RemoteClient, StatsModule, SkillsModule,
        BasicModule, CooldownModule, InventoryModule {
    /** v83 客户端旗标字的逐位拼装——协议出口的唯一组装点。逐枚举取位，不假设
     *  underlying 值连续或对齐；服务端逻辑不得读写整型旗标视图。 */
    public static int assembleClientFlagBits(org.gms.client.inventory.Item item) {
        int bits = 0;
        boolean equipType = item.getInventoryTab() == org.gms.client.inventory.InventoryType.EQUIP;
        for (org.gms.client.inventory.ItemFlag f : org.gms.client.inventory.ItemFlag.values()) {
            if (!item.hasFlag(f)) {
                continue;
            }
            if (f == org.gms.client.inventory.ItemFlag.SCISSOR_USABLE) {
                continue;   // 服务端语义标签，客户端无此位
            }
            if (f == org.gms.client.inventory.ItemFlag.TRADE_ONCE) {
                // karma 出口按类别取旧位
                bits |= equipType ? org.gms.client.inventory.ItemFlag.LEGACY_KARMA_EQP
                                  : org.gms.client.inventory.ItemFlag.LEGACY_KARMA_USE;
                continue;
            }
            bits |= f.legacyValue();
        }
        org.gms.client.inventory.Equip equipInfo = item.getEquipInfo();
        if (equipInfo != null) {
            for (org.gms.client.inventory.EquipFlag f : org.gms.client.inventory.EquipFlag.values()) {
                if (equipInfo.hasFlag(f)) {
                    bits |= f.legacyValue();
                }
            }
        }
        return bits;
    }

    private final Client client;

    /** 当前书写段（null = 无作用域，语义调用即时处理并冲刷）；嵌套经 parent 链接 */
    private ScopeLog active;

    /** 各 opcode 编码器（flush 按此固定顺序发送，保证确定性） */
    private final StatChangedOp statChanged = new StatChangedOp();
    private final UpdateSkillsOp updateSkills = new UpdateSkillsOp();
    private final CooldownOp cooldown = new CooldownOp();
    private final ModifyInventoryOp inventory = new ModifyInventoryOp();

    public V83RemoteClient(Client client) {
        this.client = client;
    }

    @Override
    public synchronized RemoteUpdate update() {
        active = new ScopeLog(active);
        return new Handle(active);
    }

    private final class Handle implements RemoteUpdate {
        // 会话内书写与 RemoteClient 快捷通道是同一批模块单例：
        // 写入当前段（无作用域则即时处理并冲刷）——句柄仅持有自己的段与生命周期。

        private final ScopeLog mine;
        private boolean done = false;

        Handle(ScopeLog log) {
            this.mine = log;
        }

        /** 句柄只允许操作仍处于栈顶的段（过期句柄=编程错误，显式暴露而非静默错丢） */
        private ScopeLog popMine() {
            if (active != mine) {
                throw new IllegalStateException("事务作用域已终结或非栈顶");
            }
            ScopeLog closed = mine;
            active = closed.parent();
            return closed;
        }

        @Override public StatsModule stats() { return V83RemoteClient.this; }

        @Override public SkillsModule skills() { return V83RemoteClient.this; }

        @Override public BasicModule basic() { return V83RemoteClient.this; }

        @Override public CooldownModule cooldown() { return V83RemoteClient.this; }

        @Override public InventoryModule inventory() { return V83RemoteClient.this; }

        /** 丢弃本段全部事件并结束（P2：O(1) 弃段，无任何截断推理）。终态操作。 */
        @Override
        public synchronized void drop() {
            if (done) {
                throw new IllegalStateException("事务作用域已终结（drop/close 只允许一次）");
            }
            done = true;
            popMine();
        }

        @Override
        public void commit() {
            close();
        }

        @Override
        public synchronized void close() {
            if (done) {
                return;
            }
            done = true;
            ScopeLog closed = popMine();
            if (active == null) {
                for (SemanticEvent e : closed.events()) {
                    process(e);
                }
                flush();
            } else {
                active.adopt(closed);
            }
        }
    }

    // ── 语义模块：写入当前段（未开域则即时处理并冲刷）；合并是消费侧的 ──

    @Override
    public synchronized void updateStats(StatsUpdate update) {
        write(new SemanticEvent.Stats(update));
    }

    @Override
    public synchronized void updateSp(SpUpdate update) {
        write(new SemanticEvent.Sp(update));
    }

    @Override
    public synchronized void updateBasic(BasicUpdate update) {
        write(new SemanticEvent.Basic(update));
    }

    @Override
    public synchronized void unlockActions() {
        write(new SemanticEvent.UnlockActions());
    }

    @Override
    public synchronized void updateSkill(SkillUpdate update) {
        write(new SemanticEvent.Skill(update));
    }

    @Override
    public synchronized void removeSkill(int skillId) {
        write(new SemanticEvent.SkillRemove(skillId));
    }

    @Override
    public synchronized void clearSkillCooldown(int skillId) {
        write(new SemanticEvent.CooldownClear(skillId));
    }

    @Override
    public synchronized void updateInventory(List<SlotChange> changes) {
        write(new SemanticEvent.InventoryMods(changes));
    }

    @Override
    public synchronized void announceInventoryFull() {
        write(new SemanticEvent.InventoryFull());
    }

    private void write(SemanticEvent e) {
        if (active == null) {
            process(e);
            flush();
        } else {
            active.append(e);
        }
    }

    /** 唯一的事件处理入口——与"未开域逐条调用"共用，批量化正确性的立足点 */
    private void process(SemanticEvent e) {
        if (e instanceof SemanticEvent.Stats(var u)) {
            statChanged.mergeStats(u);
        } else if (e instanceof SemanticEvent.Sp(var u)) {
            statChanged.mergeSp(u);
        } else if (e instanceof SemanticEvent.Basic(var u)) {
            statChanged.mergeBasic(u);
        } else if (e instanceof SemanticEvent.UnlockActions) {
            statChanged.unlockActions();
        } else if (e instanceof SemanticEvent.Skill(var u)) {
            updateSkills.merge(u);
        } else if (e instanceof SemanticEvent.SkillRemove(int skillId)) {
            updateSkills.mergeRemove(skillId);
        } else if (e instanceof SemanticEvent.CooldownClear(int skillId)) {
            cooldown.mergeClear(skillId);
        } else if (e instanceof SemanticEvent.InventoryMods(var changes)) {
            inventory.mergeAll(changes);
        } else if (e instanceof SemanticEvent.InventoryFull) {
            inventory.markFull();
        }
    }

    // ── 模块访问器：v83 侧同一对象同时承担语义入口与组装后端 ──

    @Override public StatsModule stats() { return this; }

    @Override public SkillsModule skills() { return this; }

    @Override public BasicModule basic() { return this; }

    @Override public CooldownModule cooldown() { return this; }

    @Override public InventoryModule inventory() { return this; }

    private void flushIfImmediate() {
        if (active == null) {
            flush();
        }
    }

    /** 按 op 固定顺序发送非空队列并清空 */
    private void flush() {
        statChanged.sendTo(client);
        updateSkills.sendTo(client);
        cooldown.sendTo(client);
        inventory.sendTo(client);
    }
}
