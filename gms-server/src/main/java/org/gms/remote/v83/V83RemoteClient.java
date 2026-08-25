package org.gms.remote.v83;

import org.gms.client.Client;
import org.gms.remote.BasicUpdate;
import org.gms.remote.RemoteClient;
import org.gms.remote.RemoteUpdate;
import org.gms.remote.SkillUpdate;
import org.gms.remote.SpUpdate;
import org.gms.remote.StatsUpdate;

/**
 * RemoteClient 的 v83 实现：内置有状态合并域（见 RemoteClient 类注释）。
 * 本类只负责域管理（depth）与语义调用到 opcode 编码器的**路由**——
 * 每个 opcode 一个编码器（{@link StatChangedOp}/{@link UpdateSkillsOp}/{@link CooldownOp}），
 * update→op 为多对多（如 stats/sp/basic 域合并进同一 STAT_CHANGED；removeSkill 复用 UPDATE_SKILLS）。
 * 未开域时语义调用立即发送；开域期间入队，最外层域关闭时按固定顺序逐 op 编码发送。
 */
public final class V83RemoteClient implements RemoteClient {
    private final Client client;

    /** 合并域深度（0 = 未开域，语义调用立即发送） */
    private int depth = 0;

    /** 各 opcode 编码器（flush 按此固定顺序发送，保证确定性） */
    private final StatChangedOp statChanged = new StatChangedOp();
    private final UpdateSkillsOp updateSkills = new UpdateSkillsOp();
    private final CooldownOp cooldown = new CooldownOp();

    public V83RemoteClient(Client client) {
        this.client = client;
    }

    @Override
    public synchronized RemoteUpdate update() {
        depth++;
        return new Handle();
    }

    private final class Handle implements RemoteUpdate {
        @Override
        public RemoteUpdate updateStats(StatsUpdate update) {
            V83RemoteClient.this.updateStats(update);
            return this;
        }

        @Override
        public RemoteUpdate updateSp(SpUpdate update) {
            V83RemoteClient.this.updateSp(update);
            return this;
        }

        @Override
        public RemoteUpdate updateBasic(BasicUpdate update) {
            V83RemoteClient.this.updateBasic(update);
            return this;
        }

        @Override
        public RemoteUpdate unlockActions() {
            V83RemoteClient.this.unlockActions();
            return this;
        }

        @Override
        public RemoteUpdate updateSkill(SkillUpdate update) {
            V83RemoteClient.this.updateSkill(update);
            return this;
        }

        @Override
        public RemoteUpdate removeSkill(int skillId) {
            V83RemoteClient.this.removeSkill(skillId);
            return this;
        }

        @Override
        public RemoteUpdate clearSkillCooldown(int skillId) {
            V83RemoteClient.this.clearSkillCooldown(skillId);
            return this;
        }

        @Override
        public void commit() {
            close();
        }

        @Override
        public void close() {
            endDomain();
        }
    }

    private synchronized void endDomain() {
        if (--depth <= 0) {
            depth = 0;
            flush();
        }
    }

    // ── 语义调用路由：并入对应 op 的待发队列；未开域时立即发送 ──

    @Override
    public synchronized void updateStats(StatsUpdate update) {
        statChanged.mergeStats(update);
        flushIfImmediate();
    }

    @Override
    public synchronized void updateSp(SpUpdate update) {
        statChanged.mergeSp(update);
        flushIfImmediate();
    }

    @Override
    public synchronized void updateBasic(BasicUpdate update) {
        statChanged.mergeBasic(update);
        flushIfImmediate();
    }

    @Override
    public synchronized void unlockActions() {
        statChanged.unlockActions();
        flushIfImmediate();
    }

    @Override
    public synchronized void updateSkill(SkillUpdate update) {
        updateSkills.merge(update);
        flushIfImmediate();
    }

    @Override
    public synchronized void removeSkill(int skillId) {
        updateSkills.mergeRemove(skillId);
        flushIfImmediate();
    }

    @Override
    public synchronized void clearSkillCooldown(int skillId) {
        cooldown.mergeClear(skillId);
        flushIfImmediate();
    }

    private void flushIfImmediate() {
        if (depth == 0) {
            flush();
        }
    }

    /** 按 op 固定顺序发送非空队列并清空 */
    private void flush() {
        statChanged.sendTo(client);
        updateSkills.sendTo(client);
        cooldown.sendTo(client);
    }
}
