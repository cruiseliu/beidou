/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.client.character;

import org.gms.client.Stat;

import lombok.Getter;
import lombok.Setter;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.server.maps.AbstractAnimatedMapObject;
import org.gms.server.maps.MapleMap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author RonanLana
 */
public abstract class AbstractCharacterObject extends AbstractAnimatedMapObject {
    @Setter
    @Getter
    protected MapleMap map;
    protected final CharacterStats stats = new CharacterStats();
    @Setter
    protected int hpMpApUsed;
    @Setter
    protected int remainingAp;
    protected int[] remainingSp = new int[10];

    private AbstractCharacterListener listener = null;
    protected Map<Stat, Integer> statUpdates = new HashMap<>();

    protected final Lock effLock = new ReentrantLock(true);
    protected final Lock statRlock;
    protected final Lock statWlock;

    protected AbstractCharacterObject() {
        ReadWriteLock statLock = new ReentrantReadWriteLock(true);
        this.statRlock = statLock.readLock();
        this.statWlock = statLock.writeLock();
        Arrays.fill(remainingSp, 0);
    }

    protected void setListener(AbstractCharacterListener listener) {
        this.listener = listener;
    }

    public int getStr() {
        statRlock.lock();
        try {
            return stats.str;
        } finally {
            statRlock.unlock();
        }
    }

    public int getDex() {
        statRlock.lock();
        try {
            return stats.dex;
        } finally {
            statRlock.unlock();
        }
    }

    public int getInt() {
        statRlock.lock();
        try {
            return stats.int_;
        } finally {
            statRlock.unlock();
        }
    }

    public int getLuk() {
        statRlock.lock();
        try {
            return stats.luk;
        } finally {
            statRlock.unlock();
        }
    }

    public int getRemainingAp() {
        statRlock.lock();
        try {
            return remainingAp;
        } finally {
            statRlock.unlock();
        }
    }

    protected int getRemainingSp(int jobid) {
        statRlock.lock();
        try {
            return remainingSp[GameConstants.getSkillBook(jobid)];
        } finally {
            statRlock.unlock();
        }
    }

    public int[] getRemainingSps() {
        statRlock.lock();
        try {
            return Arrays.copyOf(remainingSp, remainingSp.length);
        } finally {
            statRlock.unlock();
        }
    }

    public int getHpMpApUsed() {
        statRlock.lock();
        try {
            return hpMpApUsed;
        } finally {
            statRlock.unlock();
        }
    }

    public boolean isAlive() {
        statRlock.lock();
        try {
            return stats.hp > 0;
        } finally {
            statRlock.unlock();
        }
    }

    public int getHp() {
        statRlock.lock();
        try {
            return stats.hp;
        } finally {
            statRlock.unlock();
        }
    }

    public int getMp() {
        statRlock.lock();
        try {
            return stats.mp;
        } finally {
            statRlock.unlock();
        }
    }

    public int getMaxHp() {
        statRlock.lock();
        try {
            return stats.maxHp;
        } finally {
            statRlock.unlock();
        }
    }

    public int getMaxMp() {
        statRlock.lock();
        try {
            return stats.maxMp;
        } finally {
            statRlock.unlock();
        }
    }

    public int getCurrentMaxHp() {
        return stats.localMaxHp;
    }

    public int getCurrentMaxMp() {
        return stats.localMaxMp;
    }

    private void dispatchHpChanged(final int oldHp) {
        listener.onHpChanged(oldHp);
    }

    private void dispatchHpMpPoolUpdated() {
        listener.onHpMpPoolUpdate();
    }

    private void dispatchStatUpdated() {
        listener.onStatUpdate();
    }

    private void dispatchStatPoolUpdateAnnounced() {
        listener.onAnnounceStatPoolUpdate();
    }

    protected void setHp(int newHp) {
        int oldHp = stats.hp;
        stats.setHp(newHp);
        dispatchHpChanged(oldHp);
    }

    protected void setMp(int newMp) {
        stats.setMp(newMp);
    }

    public void setRemainingSp(int remainingSp, int skillbook) {
        this.remainingSp[skillbook] = remainingSp;
    }

    protected void setMaxHp(int hp_) {
        stats.setMaxHp(hp_);
    }

    protected void setMaxMp(int mp_) {
        stats.setMaxMp(mp_);
    }

    private static long clampStat(int v, int min, int max) {
        return (v < min) ? min : ((v > max) ? max : v);
    }

    private static long calcStatPoolNode(Integer v, int displacement) {
        long r;
        if (v == null) {
            r = -32768;
        } else {
            r = clampStat(v, -32767, 32767);
        }

        return ((r & 0x0FFFF) << displacement);
    }

    private static long calcStatPoolLong(Integer v1, Integer v2, Integer v3, Integer v4) {
        long ret = 0;

        ret |= calcStatPoolNode(v1, 48);
        ret |= calcStatPoolNode(v2, 32);
        ret |= calcStatPoolNode(v3, 16);
        ret |= calcStatPoolNode(v4, 0);

        return ret;
    }

    private void changeStatPool(Long hpMpPool, Long strDexIntLuk, Long newSp, int newAp, boolean silent) {
        effLock.lock();
        statWlock.lock();
        try {
            statUpdates.clear();
            boolean poolUpdate = false;
            boolean statUpdate = false;

            if (hpMpPool != null) {
                short newHp = (short) (hpMpPool >> 48);
                short newMp = (short) (hpMpPool >> 32);
                short newMaxHp = (short) (hpMpPool >> 16);
                short newMaxMp = hpMpPool.shortValue();

                if (newMaxHp != Short.MIN_VALUE) {
                    if (newMaxHp < 50) {
                        newMaxHp = 50;
                    }

                    poolUpdate = true;
                    setMaxHp(newMaxHp);
                    statUpdates.put(Stat.MAXHP, stats.clientMaxHp);
                    statUpdates.put(Stat.HP, stats.hp);
                }

                if (newHp != Short.MIN_VALUE) {
                    setHp(newHp);
                    statUpdates.put(Stat.HP, stats.hp);
                }

                if (newMaxMp != Short.MIN_VALUE) {
                    if (newMaxMp < 5) {
                        newMaxMp = 5;
                    }

                    poolUpdate = true;
                    setMaxMp(newMaxMp);
                    statUpdates.put(Stat.MAXMP, stats.clientMaxMp);
                    statUpdates.put(Stat.MP, stats.mp);
                }

                if (newMp != Short.MIN_VALUE) {
                    setMp(newMp);
                    statUpdates.put(Stat.MP, stats.mp);
                }
            }

            if (strDexIntLuk != null) {
                short newStr = (short) (strDexIntLuk >> 48);
                short newDex = (short) (strDexIntLuk >> 32);
                short newInt = (short) (strDexIntLuk >> 16);
                short newLuk = strDexIntLuk.shortValue();

                if (newStr >= 4) {
                    setStr(newStr);
                    statUpdates.put(Stat.STR, stats.str);
                }

                if (newDex >= 4) {
                    setDex(newDex);
                    statUpdates.put(Stat.DEX, stats.dex);
                }

                if (newInt >= 4) {
                    setInt(newInt);
                    statUpdates.put(Stat.INT, stats.int_);
                }

                if (newLuk >= 4) {
                    setLuk(newLuk);
                    statUpdates.put(Stat.LUK, stats.luk);
                }

                if (newAp >= 0) {
                    setRemainingAp(newAp);
                    statUpdates.put(Stat.AVAILABLEAP, remainingAp);
                }

                statUpdate = true;
            }

            if (newSp != null) {
                short sp = (short) (newSp >> 16);
                short skillbook = newSp.shortValue();

                setRemainingSp(sp, skillbook);
                statUpdates.put(Stat.AVAILABLESP, remainingSp[skillbook]);
            }

            if (!statUpdates.isEmpty()) {
                if (poolUpdate) {
                    dispatchHpMpPoolUpdated();
                }

                if (statUpdate) {
                    dispatchStatUpdated();
                }

                if (!silent) {
                    dispatchStatPoolUpdateAnnounced();
                }
            }
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void healHpMp() {
        updateHpMp(30000);
    }

    public void updateHpMp(int x) {
        updateHpMp(x, x);
    }

    public void updateHpMp(int newhp, int newmp) {
        changeHpMp(newhp, newmp, false);
    }

    public void changeHpMp(int newhp, int newmp, boolean silent) {
        changeHpMpPool(newhp, newmp, null, null, silent);
    }

    private void changeHpMpPool(Integer hp, Integer mp, Integer maxhp, Integer maxmp, boolean silent) {
        long hpMpPool = calcStatPoolLong(hp, mp, maxhp, maxmp);
        changeStatPool(hpMpPool, null, null, -1, silent);
    }

    public void updateHp(int hp) {
        updateHpMaxHp(hp, null);
    }

    public void updateMaxHp(int maxhp) {
        updateHpMaxHp(null, maxhp);
    }

    public void updateHpMaxHp(int hp, int maxhp) {
        updateHpMaxHp(Integer.valueOf(hp), Integer.valueOf(maxhp));
    }

    private void updateHpMaxHp(Integer hp, Integer maxhp) {
        changeHpMpPool(hp, null, maxhp, null, false);
    }

    public void updateMp(int mp) {
        updateMpMaxMp(mp, null);
    }

    public void updateMaxMp(int maxmp) {
        updateMpMaxMp(null, maxmp);
    }

    public void updateMpMaxMp(int mp, int maxmp) {
        updateMpMaxMp(Integer.valueOf(mp), Integer.valueOf(maxmp));
    }

    private void updateMpMaxMp(Integer mp, Integer maxmp) {
        changeHpMpPool(null, mp, null, maxmp, false);
    }

    public void updateMaxHpMaxMp(int maxhp, int maxmp) {
        changeHpMpPool(null, null, maxhp, maxmp, false);
    }

    protected void enforceMaxHpMp() {
        effLock.lock();
        statWlock.lock();
        try {
            if (stats.mp > stats.localMaxMp || stats.hp > stats.localMaxHp) {
                changeHpMp(stats.hp, stats.mp, false);
            }
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public int safeAddHP(int delta) {
        effLock.lock();
        statWlock.lock();
        try {
            if (stats.hp + delta <= 0) {
                delta = -stats.hp + 1;
            }

            addHP(delta);
            return delta;
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void addHP(int delta) {
        effLock.lock();
        statWlock.lock();
        try {
            updateHp(stats.hp + delta);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void addMP(int delta) {
        effLock.lock();
        statWlock.lock();
        try {
            updateMp(stats.mp + delta);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void addMPHP(int hpDelta, int mpDelta) {
        effLock.lock();
        statWlock.lock();
        try {
            updateHpMp(stats.hp + hpDelta, stats.mp + mpDelta);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    protected void addMaxMPMaxHP(int hpdelta, int mpdelta, boolean silent) {
        effLock.lock();
        statWlock.lock();
        try {
            changeHpMpPool(null, null, stats.maxHp + hpdelta, stats.maxMp + mpdelta, silent);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void addMaxHP(int delta) {
        effLock.lock();
        statWlock.lock();
        try {
            updateMaxHp(stats.maxHp + delta);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void addMaxMP(int delta) {
        effLock.lock();
        statWlock.lock();
        try {
            updateMaxMp(stats.maxMp + delta);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void setStr(int str) {
        this.stats.str = str;
    }

    public void setDex(int dex) {
        this.stats.dex = dex;
    }

    public void setInt(int int_) {
        this.stats.int_ = int_;
    }

    public void setLuk(int luk) {
        this.stats.luk = luk;
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
        effLock.lock();
        statWlock.lock();
        try {
            if (remainingAp - deltaAp < 0 || hpMpApUsed + deltaAp < 0 || stats.maxHp >= 30000) {
                return false;
            }

            long hpMpPool = calcStatPoolLong(null, null, stats.maxHp + deltaHP, stats.maxMp);
            long strDexIntLuk = calcStatPoolLong(stats.str, stats.dex, stats.int_, stats.luk);

            changeStatPool(hpMpPool, strDexIntLuk, null, remainingAp - deltaAp, false);
            setHpMpApUsed(hpMpApUsed + deltaAp);
            return true;
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public boolean assignMP(int deltaMP, int deltaAp) {
        effLock.lock();
        statWlock.lock();
        try {
            if (remainingAp - deltaAp < 0 || hpMpApUsed + deltaAp < 0 || stats.maxMp >= 30000) {
                return false;
            }

            long hpMpPool = calcStatPoolLong(null, null, stats.maxHp, stats.maxMp + deltaMP);
            long strDexIntLuk = calcStatPoolLong(stats.str, stats.dex, stats.int_, stats.luk);

            changeStatPool(hpMpPool, strDexIntLuk, null, remainingAp - deltaAp, false);
            setHpMpApUsed(hpMpApUsed + deltaAp);
            return true;
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    private static int apAssigned(Integer x) {
        return x != null ? x : 0;
    }

    public boolean assignStrDexIntLuk(int deltaStr, int deltaDex, int deltaInt, int deltaLuk) {
        return assignStrDexIntLuk(Integer.valueOf(deltaStr), Integer.valueOf(deltaDex), Integer.valueOf(deltaInt), Integer.valueOf(deltaLuk));
    }

    private boolean assignStrDexIntLuk(Integer deltaStr, Integer deltaDex, Integer deltaInt, Integer deltaLuk) {
        effLock.lock();
        statWlock.lock();
        try {
            int apUsed = apAssigned(deltaStr) + apAssigned(deltaDex) + apAssigned(deltaInt) + apAssigned(deltaLuk);
            if (apUsed > remainingAp) {
                return false;
            }

            int newStr = stats.str, newDex = stats.dex, newInt = stats.int_, newLuk = stats.luk;
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
            updateStrDexIntLuk(newStr, newDex, newInt, newLuk, newAp);
            return true;
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void updateStrDexIntLuk(int x) {
        updateStrDexIntLuk(x, x, x, x, -1);
    }

    public void changeRemainingAp(int x, boolean silent) {
        effLock.lock();
        statWlock.lock();
        try {
            changeStrDexIntLuk(stats.str, stats.dex, stats.int_, stats.luk, x, silent);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    public void gainAp(int deltaAp, boolean silent) {
        effLock.lock();
        statWlock.lock();
        try {
            changeRemainingAp(Math.max(0, remainingAp + deltaAp), silent);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    protected void updateStrDexIntLuk(int str, int dex, int int_, int luk, int remainingAp) {
        changeStrDexIntLuk(str, dex, int_, luk, remainingAp, false);
    }

    private void changeStrDexIntLuk(Integer str, Integer dex, Integer int_, Integer luk, int remainingAp, boolean silent) {
        long strDexIntLuk = calcStatPoolLong(str, dex, int_, luk);
        changeStatPool(null, strDexIntLuk, null, remainingAp, silent);
    }

    private void changeStrDexIntLukSp(Integer str, Integer dex, Integer int_, Integer luk, int remainingAp, int remainingSp, int skillbook, boolean silent) {
        long strDexIntLuk = calcStatPoolLong(str, dex, int_, luk);
        long sp = calcStatPoolLong(0, 0, remainingSp, skillbook);
        changeStatPool(null, strDexIntLuk, sp, remainingAp, silent);
    }

    protected void updateStrDexIntLukSp(int str, int dex, int int_, int luk, int remainingAp, int remainingSp, int skillbook) {
        changeStrDexIntLukSp(str, dex, int_, luk, remainingAp, remainingSp, skillbook, false);
    }

    protected void setRemainingSp(int[] sps) {
        effLock.lock();
        statWlock.lock();
        try {
            System.arraycopy(sps, 0, remainingSp, 0, sps.length);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }

    protected void updateRemainingSp(int remainingSp, int skillbook) {
        changeRemainingSp(remainingSp, skillbook, false);
    }

    protected void changeRemainingSp(int remainingSp, int skillbook, boolean silent) {
        long sp = calcStatPoolLong(0, 0, remainingSp, skillbook);
        changeStatPool(null, null, sp, Short.MIN_VALUE, silent);
    }

    public void gainSp(int deltaSp, int skillbook, boolean silent) {
        effLock.lock();
        statWlock.lock();
        try {
            changeRemainingSp(Math.max(0, remainingSp[skillbook] + deltaSp), skillbook, silent);
        } finally {
            statWlock.unlock();
            effLock.unlock();
        }
    }
}
