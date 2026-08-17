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
import org.gms.util.Locks;
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
    protected final CharacterAp ap = new CharacterAp(this);
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
        return ap.getRemainingAp();
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
        return ap.getHpMpApUsed();
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

    /** 应用属性更新（发包通知客户端） */
    void applyUpdate(StatsUpdate u) {
        applyUpdateInternal(u, false);
    }

    /** 静默应用属性更新（不发包，如登录加载、升级流程内部） */
    void applyUpdateSilently(StatsUpdate u) {
        applyUpdateInternal(u, true);
    }

    private void applyUpdateInternal(StatsUpdate u, boolean silent) {
        try (var ignored = Locks.acquire(effLock, statWlock)) {
            statUpdates.clear();
            boolean poolUpdate = false;
            boolean statUpdate = false;

            if (u.hp != null || u.mp != null || u.maxHp != null || u.maxMp != null) {
                if (u.maxHp != null) {
                    poolUpdate = true;
                    setMaxHp(Math.max(50, u.maxHp));
                    statUpdates.put(Stat.MAXHP, stats.clientMaxHp);
                    statUpdates.put(Stat.HP, stats.hp);
                }

                if (u.hp != null) {
                    setHp(u.hp);
                    statUpdates.put(Stat.HP, stats.hp);
                }

                if (u.maxMp != null) {
                    poolUpdate = true;
                    setMaxMp(Math.max(5, u.maxMp));
                    statUpdates.put(Stat.MAXMP, stats.clientMaxMp);
                    statUpdates.put(Stat.MP, stats.mp);
                }

                if (u.mp != null) {
                    setMp(u.mp);
                    statUpdates.put(Stat.MP, stats.mp);
                }
            }

            if (u.str != null || u.dex != null || u.int_ != null || u.luk != null || (u.ap != null && u.ap >= 0)) {
                if (u.str != null && u.str >= 4) {
                    setStr(u.str);
                    statUpdates.put(Stat.STR, stats.str);
                }

                if (u.dex != null && u.dex >= 4) {
                    setDex(u.dex);
                    statUpdates.put(Stat.DEX, stats.dex);
                }

                if (u.int_ != null && u.int_ >= 4) {
                    setInt(u.int_);
                    statUpdates.put(Stat.INT, stats.int_);
                }

                if (u.luk != null && u.luk >= 4) {
                    setLuk(u.luk);
                    statUpdates.put(Stat.LUK, stats.luk);
                }

                if (u.ap != null && u.ap >= 0) {
                    ap.remainingAp = u.ap;
                    statUpdates.put(Stat.AVAILABLEAP, ap.remainingAp);
                }

                statUpdate = true;
            }

            if (u.sp != null) {
                setRemainingSp(u.sp, u.skillbook);
                statUpdates.put(Stat.AVAILABLESP, remainingSp[u.skillbook]);
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
        }
    }

    public void healHpMp() {
        updateHpMp(30000);
    }

    public void updateHpMp(int x) {
        updateHpMp(x, x);
    }

    public void updateHpMp(int newhp, int newmp) {
        applyUpdate(new StatsUpdate().setHp(newhp).setMp(newmp));
    }

    public void changeHpMp(int newhp, int newmp, boolean silent) {
        StatsUpdate u = new StatsUpdate().setHp(newhp).setMp(newmp);
        if (silent) {
            applyUpdateSilently(u);
        } else {
            applyUpdate(u);
        }
    }

    public void updateHp(int hp) {
        applyUpdate(new StatsUpdate().setHp(hp));
    }

    public void updateMaxHp(int maxhp) {
        applyUpdate(new StatsUpdate().setMaxHp(maxhp));
    }

    public void updateHpMaxHp(int hp, int maxhp) {
        applyUpdate(new StatsUpdate().setHp(hp).setMaxHp(maxhp));
    }

    public void updateMp(int mp) {
        applyUpdate(new StatsUpdate().setMp(mp));
    }

    public void updateMaxMp(int maxmp) {
        applyUpdate(new StatsUpdate().setMaxMp(maxmp));
    }

    public void updateMpMaxMp(int mp, int maxmp) {
        applyUpdate(new StatsUpdate().setMp(mp).setMaxMp(maxmp));
    }

    public void updateMaxHpMaxMp(int maxhp, int maxmp) {
        applyUpdate(new StatsUpdate().setMaxHp(maxhp).setMaxMp(maxmp));
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
        try (var ignored = Locks.acquire(effLock, statWlock)) {
            StatsUpdate u = new StatsUpdate().setMaxHp(stats.maxHp + hpdelta).setMaxMp(stats.maxMp + mpdelta);
            if (silent) {
                applyUpdateSilently(u);
            } else {
                applyUpdate(u);
            }
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

    // ── AP 委托：实现集中在 CharacterAp ──

    public boolean assignStr(int x) {
        return ap.assignStr(x);
    }

    public boolean assignDex(int x) {
        return ap.assignDex(x);
    }

    public boolean assignInt(int x) {
        return ap.assignInt(x);
    }

    public boolean assignLuk(int x) {
        return ap.assignLuk(x);
    }

    public boolean assignHP(int deltaHP, int deltaAp) {
        return ap.assignHP(deltaHP, deltaAp);
    }

    public boolean assignMP(int deltaMP, int deltaAp) {
        return ap.assignMP(deltaMP, deltaAp);
    }

    public boolean assignStrDexIntLuk(int deltaStr, int deltaDex, int deltaInt, int deltaLuk) {
        return ap.assignStrDexIntLuk(deltaStr, deltaDex, deltaInt, deltaLuk);
    }

    public void changeRemainingAp(int x, boolean silent) {
        ap.changeRemainingAp(x, silent);
    }

    public void gainAp(int deltaAp, boolean silent) {
        ap.gainAp(deltaAp, silent);
    }

    public void updateStrDexIntLuk(int x) {
        updateStrDexIntLuk(x, x, x, x, -1);
    }

    void updateStrDexIntLuk(int str, int dex, int int_, int luk, int remainingAp) {
        StatsUpdate u = new StatsUpdate().setStr(str).setDex(dex).setInt(int_).setLuk(luk);
        if (remainingAp >= 0) {
            u.setAp(remainingAp);
        }
        applyUpdate(u);
    }

    protected void updateStrDexIntLukSp(int str, int dex, int int_, int luk, int remainingAp, int remainingSp, int skillbook) {
        StatsUpdate u = new StatsUpdate().setStr(str).setDex(dex).setInt(int_).setLuk(luk);
        if (remainingAp >= 0) {
            u.setAp(remainingAp);
        }
        u.setSp(skillbook, remainingSp);
        applyUpdate(u);
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
        StatsUpdate u = new StatsUpdate().setSp(skillbook, remainingSp);
        if (silent) {
            applyUpdateSilently(u);
        } else {
            applyUpdate(u);
        }
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
