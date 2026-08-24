package org.gms.remote;

import org.gms.client.character.Stat;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * updateStats 操作的语义载荷：CharacterStats 域的状态新值（绝对值，非增量）。
 * 同一事务内多次提交时同字段后写覆盖。
 *
 * <p>分两类入口：
 * <ul>
 * <li>面板属性（力敏智运、max hp/mp、p/m atk）走 {@link #set}——同类属性的集合语义；
 * P_ATK/M_ATK 无 wire 位，实现层丢弃；</li>
 * <li>hp/mp/ap 走各自单独方法——它们不是面板属性（是资源/货币语义），
 * 但 v83 实现层与面板共用 STAT_CHANGED 封包。</li>
 * </ul>
 */
public final class StatsUpdate {
    private final EnumMap<Stat, Integer> panel = new EnumMap<>(Stat.class);
    private Integer hp;
    private Integer mp;
    private Integer ap;

    public StatsUpdate set(Stat field, int value) {
        panel.put(field, value);
        return this;
    }

    public StatsUpdate hp(int value) {
        this.hp = value;
        return this;
    }

    public StatsUpdate mp(int value) {
        this.mp = value;
        return this;
    }

    public StatsUpdate ap(int value) {
        this.ap = value;
        return this;
    }

    public Map<Stat, Integer> panel() {
        return Collections.unmodifiableMap(panel);
    }

    /** 当前 HP 新值；未设置返回 null。 */
    public Integer hp() {
        return hp;
    }

    public Integer mp() {
        return mp;
    }

    public Integer ap() {
        return ap;
    }

    /** 无任何字段时为空（调用方用于跳过空公告）。 */
    public boolean isEmpty() {
        return panel.isEmpty() && hp == null && mp == null && ap == null;
    }
}
