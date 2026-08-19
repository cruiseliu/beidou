package org.gms.client.character;

import org.gms.config.GameConfig;
import org.gms.model.json.CharacterApData;
import org.gms.util.Locks;

import java.util.Arrays;

import static org.gms.client.character.BaseStat.*;

/**
 * AP（能力点）：数据 + 全部"仅与 AP 相关"和"将 AP 分配到属性"的逻辑。
 * 持有 owner 反向引用，AP 操作经 stats.wLock 保护（通过 Locks），派发走 owner.applyUpdate。
 * 四维/HP/MP 与 AP 的原子写入管道、发包留在 Character。
 */
public class CharacterAp {
    private final Character owner;

    int remainingAp;
    int hpMpApUsed;

    CharacterAp(Character owner) {
        this.owner = owner;
    }

    int getRemainingAp() {
        try (var ignored = Locks.acquire(owner.stats.rLock)) {
            return remainingAp;
        }
    }

    int getHpMpApUsed() {
        try (var ignored = Locks.acquire(owner.stats.rLock)) {
            return hpMpApUsed;
        }
    }

    void changeRemainingAp(int x, boolean silent) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            StatsUpdate u = new StatsUpdate();
            for (int i = 0; i < BASE_STAT_COUNT; i++) {
                u.setAttr(i, owner.stats.attrs[i]);
            }
            u.setAp(x);
            if (silent) {
                owner.applyUpdateSilently(u);
            } else {
                owner.applyUpdate(u);
            }
        }
    }

    void gainAp(int deltaAp, boolean silent) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            changeRemainingAp(Math.max(0, remainingAp + deltaAp), silent);
        }
    }

    /** 单维分配：assignAttr(STR, x) 等 */
    boolean assignAttr(int idx, int x) {
        Integer[] delta = new Integer[BASE_STAT_COUNT];
        delta[idx] = x;
        return assignAttrs(delta);
    }

    /** 多维分配：delta[i] 为 null 的维不变；AP 余额或任一维 4..max_ap 校验失败返回 false */
    boolean assignAttrs(Integer[] delta) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            int apUsed = 0;
            int[] newAttrs = Arrays.copyOf(owner.stats.attrs, BASE_STAT_COUNT);
            for (int i = 0; i < BASE_STAT_COUNT; i++) {
                if (delta[i] != null) {
                    apUsed += delta[i];
                    newAttrs[i] += delta[i];
                }
            }
            if (apUsed > remainingAp) {
                return false;
            }

            int maxAp = GameConfig.getServerInt("max_ap");
            for (int i = 0; i < BASE_STAT_COUNT; i++) {
                if (newAttrs[i] < 4 || newAttrs[i] > maxAp) {
                    return false;
                }
            }

            StatsUpdate u = new StatsUpdate();
            for (int i = 0; i < BASE_STAT_COUNT; i++) {
                u.setAttr(i, newAttrs[i]);
            }
            u.setAp(remainingAp - apUsed);
            owner.applyUpdate(u);
            return true;
        }
    }

    boolean assignHP(int deltaHP, int deltaAp) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            if (!canSpendAp(deltaAp, owner.stats.maxHp >= 30000)) {
                return false;
            }

            StatsUpdate u = new StatsUpdate()
                    .setMaxHp(owner.stats.maxHp + deltaHP).setMaxMp(owner.stats.maxMp)
                    .setAp(remainingAp - deltaAp);
            for (int i = 0; i < BASE_STAT_COUNT; i++) {
                u.setAttr(i, owner.stats.attrs[i]);
            }
            owner.applyUpdate(u);
            hpMpApUsed += deltaAp;
            return true;
        }
    }

    boolean assignMP(int deltaMP, int deltaAp) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            if (!canSpendAp(deltaAp, owner.stats.maxMp >= 30000)) {
                return false;
            }

            StatsUpdate u = new StatsUpdate()
                    .setMaxHp(owner.stats.maxHp).setMaxMp(owner.stats.maxMp + deltaMP)
                    .setAp(remainingAp - deltaAp);
            for (int i = 0; i < BASE_STAT_COUNT; i++) {
                u.setAttr(i, owner.stats.attrs[i]);
            }
            owner.applyUpdate(u);
            hpMpApUsed += deltaAp;
            return true;
        }
    }

    /**
     * 分配 AP 到 HP/MP 前的合法性校验。
     * capReached 为目标上限已达（maxHp/maxMp >= 30000），由调用方传入。
     */
    // ── 持久化数据转换（ap 域；信封组装在 Character.toData/applyData） ──

    CharacterApData toData() {
        CharacterApData d = new CharacterApData();
        d.remainingAp = remainingAp;
        d.hpMpApUsed = hpMpApUsed;
        return d;
    }

    void applyData(CharacterApData d) {
        remainingAp = d.remainingAp;
        hpMpApUsed = d.hpMpApUsed;
    }

    private boolean canSpendAp(int deltaAp, boolean capReached) {
        return remainingAp - deltaAp >= 0 && hpMpApUsed + deltaAp >= 0 && !capReached;
    }
}
