package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.server.BuffEffectData;
import org.gms.util.Locks;

import java.util.EnumMap;
import java.util.LinkedList;

/**
 * 角色附加状态层：每槽位（BuffStat）当前生效的效果值表。
 * 只负责状态的查询与增删，槽位条目携带的 StatEffect 仅用于索引和溯源（getBuffSource/isBuffFrom），
 * 不参与任何实际效果的计算——效果计算由游戏逻辑按需查询本表。
 * 写入（deploy/extract/clear 与 effects 直访）由 buff 生命周期层（CharacterBuffs）在持锁状态下进行。
 */
class CharacterEffects {
    private final Character owner;

    /** 槽位 → 激活条目（值 + 溯源），与 BuffStatValueHolder.bestApplied 簿记字段解耦 */
    final EnumMap<EffectType, EffectStatus> effects = new EnumMap<>(EffectType.class);

    CharacterEffects(Character owner) {
        this.owner = owner;
    }

    Long getBuffedStarttime(EffectType effectType) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            EffectStatus effect = effects.get(effectType);
            if (effect == null) {
                return null;
            }
            return effect.buff.startTime;
        }
    }

    Integer getBuffedValue(EffectType effectType) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            EffectStatus effect = effects.get(effectType);
            if (effect == null) {
                return null;
            }
            return effect.value;
        }
    }

    int getBuffSource(EffectType effectType) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            EffectStatus effect = effects.get(effectType);
            if (effect == null) {
                return -1;
            }
            return effect.getData().getSourceId();
        }
    }

    BuffEffectData getBuffEffect(EffectType stat) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            EffectStatus mbsvh = effects.get(stat);
            return mbsvh == null ? null : mbsvh.getData();
        }
    }

    boolean isBuffFrom(EffectType stat, org.gms.client.Skill skill) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            EffectStatus mbsvh = effects.get(stat);
            if (mbsvh == null) {
                return false;
            }
            return mbsvh.getData().isSkill() && mbsvh.getData().getSourceId() == skill.getId();
        }
    }

    /** 就地修改槽位值（如能量条 ENERGY_CHARGE），纯状态操作 */
    void setBuffedValue(EffectType effect, int value) {
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            EffectStatus mbsvh = effects.get(effect);
            if (mbsvh == null) {
                return;
            }
            mbsvh.value = value;
        }
    }

    /** 溯源查询：某源的效果是否正处于激活态 */
    boolean hasActiveBuff(int sourceid) {
        LinkedList<EffectStatus> allBuffs;
        try (var ignored = Locks.acquire(owner.effLock, owner.chrLock)) {
            allBuffs = new LinkedList<>(effects.values());
        }

        for (EffectStatus mbsvh : allBuffs) {
            if (mbsvh.getData().getBuffSourceId() == sourceid) {
                return true;
            }
        }
        return false;
    }
}
