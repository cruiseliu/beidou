package org.gms.client.character;

import org.gms.remote.RemoteUpdate;

import java.util.ArrayList;
import java.util.List;

/**
 * 属性更新语法糖：{@code stats.update().set(STR, x).add(MAX_HP, y).setHp(hp).commit()}。
 * <p>
 * set/add/growth 的 slot 为 {@link Stat} 数组槽位（STR..M_ATK，含 MAX_HP/MAX_MP/P_ATK/M_ATK）；
 * hp/mp/ap 是快照标量，走专用 setXxx/addXxx 方法。growth 是"有加有乘"的升级/转职成长。
 * <p>
 * silent 是提交时机的属性，由提交变体表达：{@link #commit()} 发包通知客户端、
 * {@link #commitSilently()} 静默应用（调用方自行公告/组装包）——builder 本身不携带 silent 状态。
 * <p>
 * 注意：builder 链在【锁外】构建，仅 commit 时才持 wLock。禁止"先读后写"——例如
 * {@code set(getXxx() - delta)}：getXxx() 在锁外读的是并发前快照，写覆盖会丢并发更新；
 * 相对修改一律用增量 {@code add(...)}（如 {@code addAp(-delta)}），事务内读旧快照值，原子。
 */
public final class StatUpdateBuilder {
    private final CharacterStats stats;
    private final List<Change> changes = new ArrayList<>();

    StatUpdateBuilder(CharacterStats stats) {
        this.stats = stats;
    }

    // ── attrs 槽位（StatIndex.STR..M_ATK） ──

    public StatUpdateBuilder set(Stat s, int value) {
        changes.add(new Change.Set(Change.Prop.valueOf(s.name()), value));
        return this;
    }

    public StatUpdateBuilder add(Stat s, int delta) {
        changes.add(new Change.Add(Change.Prop.valueOf(s.name()), delta));
        return this;
    }

    /** 乘法：NEW = OLD * multiplier（有加有乘的升级/转职成长 = add(加成) + multiply(系数)） */
    public StatUpdateBuilder multiply(Stat s, double multiplier) {
        changes.add(new Change.Multiply(Change.Prop.valueOf(s.name()), multiplier));
        return this;
    }

    // ── 快照标量（HP/MP/AP） ──

    public StatUpdateBuilder setHp(int value) {
        changes.add(new Change.Set(Change.Prop.HP, value));
        return this;
    }

    public StatUpdateBuilder setMp(int value) {
        changes.add(new Change.Set(Change.Prop.MP, value));
        return this;
    }

    public StatUpdateBuilder setAp(int value) {
        changes.add(new Change.Set(Change.Prop.AP, value));
        return this;
    }

    public StatUpdateBuilder addHp(int delta) {
        changes.add(new Change.Add(Change.Prop.HP, delta));
        return this;
    }

    public StatUpdateBuilder addMp(int delta) {
        changes.add(new Change.Add(Change.Prop.MP, delta));
        return this;
    }

    public StatUpdateBuilder addAp(int delta) {
        changes.add(new Change.Add(Change.Prop.AP, delta));
        return this;
    }

    // ── 提交（silent 在此抉择） ──

    /** 应用并发包通知客户端 */
    public void commit() {
        stats.updateInternal(false, changes.toArray(new Change[0]));
    }

    /** 应用（不发包，调用方自行公告/组装包） */
    public void commitSilently() {
        stats.updateInternal(true, changes.toArray(new Change[0]));
    }

    /** 应用并写入外部事务（stats 与 sp 等分域各自 update，由开事务方最终 commit） */
    public void commitInto(RemoteUpdate tx) {
        stats.updateInternal(tx, changes.toArray(new Change[0]));
    }
}
