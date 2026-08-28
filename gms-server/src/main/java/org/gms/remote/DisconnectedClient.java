package org.gms.remote;

import java.util.List;

/** 无连接实现：全部语义调用静默容忍（对齐 Character.sendPacket 对 client==null 的行为）。 */
final class DisconnectedClient implements RemoteClient, StatsModule, SkillsModule,
        BasicModule, CooldownModule, InventoryModule {
    /** 供各空实现共享的静默单例（模块调用即本类空体） */
    static final DisconnectedClient INSTANCE = new DisconnectedClient();

    static final RemoteUpdate NOOP_UPDATE = new RemoteUpdate() {
        // 模块访问器路由到静默单例自身（断线角色的会话书写安全无害）
        // 模块访问器路由到静默单例自身（断线角色的会话书写安全无害）
        @Override public StatsModule stats() { return INSTANCE; }
        @Override public SkillsModule skills() { return INSTANCE; }
        @Override public BasicModule basic() { return INSTANCE; }
        @Override public CooldownModule cooldown() { return INSTANCE; }
        @Override public InventoryModule inventory() { return INSTANCE; }
        @Override public void commit() {
        }

        @Override
        public void close() {
        }

        @Override
        public void drop() {
        }
    };

    @Override
    public RemoteUpdate update() {
        return NOOP_UPDATE;
    }

    // ── 五个模块 = 自身；全部空体 ──

    @Override public void updateStats(StatsUpdate update) {
    }

    @Override public void updateSp(SpUpdate update) {
    }

    @Override public void updateSkill(SkillUpdate update) {
    }

    @Override public void removeSkill(int skillId) {
    }

    @Override public void updateBasic(BasicUpdate update) {
    }

    @Override public void unlockActions() {
    }

    @Override public void clearSkillCooldown(int skillId) {
    }

    @Override public void updateInventory(List<SlotChange> changes) {
    }

    @Override public void announceInventoryFull() {
    }

    // ── 模块访问器返回自身（同为空实现） ──

    @Override public StatsModule stats() {
        return this;
    }

    @Override public SkillsModule skills() {
        return this;
    }

    @Override public BasicModule basic() {
        return this;
    }

    @Override public CooldownModule cooldown() {
        return this;
    }

    @Override public InventoryModule inventory() {
        return this;
    }
}
