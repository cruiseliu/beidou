package org.gms.client.character;

import org.gms.config.GameConfig;
import org.gms.util.Locks;

/**
 * AP（能力点）：数据 + 全部"仅与 AP 相关"和"将 AP 分配到属性"的逻辑。
 * 持有 owner 反向引用，Locks 使用其锁（effLock/statWlock），派发走 owner.applyUpdate。
 * 四维/HP/MP 与 AP 的原子写入管道、发包留在 AbstractCharacterObject。
 */
public class CharacterAp {
    private final AbstractCharacterObject owner;

    int remainingAp;
    int hpMpApUsed;

    CharacterAp(AbstractCharacterObject owner) {
        this.owner = owner;
    }

    public int getRemainingAp() {
        try (var ignored = Locks.acquire(owner.statRlock)) {
            return remainingAp;
        }
    }

    public int getHpMpApUsed() {
        try (var ignored = Locks.acquire(owner.statRlock)) {
            return hpMpApUsed;
        }
    }

    public void changeRemainingAp(int x, boolean silent) {
        try (var ignored = Locks.acquire(owner.effLock, owner.statWlock)) {
            StatsUpdate u = new StatsUpdate()
                    .setStr(owner.stats.str).setDex(owner.stats.dex).setInt(owner.stats.int_).setLuk(owner.stats.luk)
                    .setAp(x);
            if (silent) {
                owner.applyUpdateSilently(u);
            } else {
                owner.applyUpdate(u);
            }
        }
    }

    public void gainAp(int deltaAp, boolean silent) {
        try (var ignored = Locks.acquire(owner.effLock, owner.statWlock)) {
            changeRemainingAp(Math.max(0, remainingAp + deltaAp), silent);
        }
    }

    public boolean assignStr(int x) {
        return assignStrDexIntLuk(x, null, null, null);
    }

    public boolean assignDex(int x) {
        return assignStrDexIntLuk(null, x, null, null);
    }

    public boolean assignInt(int x) {
        return assignStrDexIntLuk(null, null, x, null);
    }

    public boolean assignLuk(int x) {
        return assignStrDexIntLuk(null, null, null, x);
    }

    public boolean assignHP(int deltaHP, int deltaAp) {
        try (var ignored = Locks.acquire(owner.effLock, owner.statWlock)) {
            if (!canSpendAp(deltaAp, owner.stats.maxHp >= 30000)) {
                return false;
            }

            owner.applyUpdate(new StatsUpdate()
                    .setMaxHp(owner.stats.maxHp + deltaHP).setMaxMp(owner.stats.maxMp)
                    .setStr(owner.stats.str).setDex(owner.stats.dex).setInt(owner.stats.int_).setLuk(owner.stats.luk)
                    .setAp(remainingAp - deltaAp));
            hpMpApUsed += deltaAp;
            return true;
        }
    }

    public boolean assignMP(int deltaMP, int deltaAp) {
        try (var ignored = Locks.acquire(owner.effLock, owner.statWlock)) {
            if (!canSpendAp(deltaAp, owner.stats.maxMp >= 30000)) {
                return false;
            }

            owner.applyUpdate(new StatsUpdate()
                    .setMaxHp(owner.stats.maxHp).setMaxMp(owner.stats.maxMp + deltaMP)
                    .setStr(owner.stats.str).setDex(owner.stats.dex).setInt(owner.stats.int_).setLuk(owner.stats.luk)
                    .setAp(remainingAp - deltaAp));
            hpMpApUsed += deltaAp;
            return true;
        }
    }

    public boolean assignStrDexIntLuk(int deltaStr, int deltaDex, int deltaInt, int deltaLuk) {
        return assignStrDexIntLuk(Integer.valueOf(deltaStr), Integer.valueOf(deltaDex), Integer.valueOf(deltaInt), Integer.valueOf(deltaLuk));
    }

    private boolean assignStrDexIntLuk(Integer deltaStr, Integer deltaDex, Integer deltaInt, Integer deltaLuk) {
        try (var ignored = Locks.acquire(owner.effLock, owner.statWlock)) {
            int apUsed = apAssigned(deltaStr) + apAssigned(deltaDex) + apAssigned(deltaInt) + apAssigned(deltaLuk);
            if (apUsed > remainingAp) {
                return false;
            }

            int newStr = owner.stats.str, newDex = owner.stats.dex, newInt = owner.stats.int_, newLuk = owner.stats.luk;
            if (deltaStr != null) {
                newStr += deltaStr;   // thanks Rohenn for noticing an NPE case after "null" started being used
            }
            if (deltaDex != null) {
                newDex += deltaDex;
            }
            if (deltaInt != null) {
                newInt += deltaInt;
            }
            if (deltaLuk != null) {
                newLuk += deltaLuk;
            }

            if (newStr < 4 || newStr > GameConfig.getServerInt("max_ap")) {
                return false;
            }

            if (newDex < 4 || newDex > GameConfig.getServerInt("max_ap")) {
                return false;
            }

            if (newInt < 4 || newInt > GameConfig.getServerInt("max_ap")) {
                return false;
            }

            if (newLuk < 4 || newLuk > GameConfig.getServerInt("max_ap")) {
                return false;
            }

            int newAp = remainingAp - apUsed;
            owner.updateStrDexIntLuk(newStr, newDex, newInt, newLuk, newAp);
            return true;
        }
    }

    static int apAssigned(Integer x) {
        return x != null ? x : 0;
    }

    /**
     * 分配 AP 到 HP/MP 前的合法性校验。
     * capReached 为目标上限已达（maxHp/maxMp >= 30000），由调用方传入。
     */
    private boolean canSpendAp(int deltaAp, boolean capReached) {
        return remainingAp - deltaAp >= 0 && hpMpApUsed + deltaAp >= 0 && !capReached;
    }
}
