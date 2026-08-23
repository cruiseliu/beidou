package org.gms.client.character;

import org.gms.config.GameConfig;
import org.gms.model.json.CharacterApData;
import org.gms.util.Locks;

import java.util.Arrays;

import static org.gms.client.character.Stat.*;

/**
 * AP（能力点）：数据 + 全部"仅与 AP 相关"和"将 AP 分配到属性"的逻辑。
 * <p>
 * remainingAp 已收敛进 {@link CharacterStats} 的 {@link StatsSnapshot}（与 attrs 同事务原子发布——
 * 分配 AP 时"属性已加且剩余 AP 已减"对读者永远一致）；本组件保留校验与分配编排，
 * 写操作经 owner.stats.update()...commit()/commitSilently() 事务。hpMpApUsed 是"洗点消耗累计"，
 * 与快照无耦合，保持本组件字段。
 */
public class CharacterAp {
    private final Character owner;

    int hpMpApUsed;

    CharacterAp(Character owner) {
        this.owner = owner;
    }

    int getRemainingAp() {
        return owner.stats.getRemainingAp();
    }

    int getHpMpApUsed() {
        try (var ignored = Locks.acquire(owner.stats.rLock)) {
            return hpMpApUsed;
        }
    }

    void changeRemainingAp(int x, boolean silent) {
        if (silent) {
            owner.stats.update().setAp(x).commitSilently();
        } else {
            owner.stats.update().setAp(x).commit();
        }
    }

    void gainAp(int deltaAp, boolean silent) {
        // 增量在事务内读旧快照（addAp 自带 >=0 下限），避免锁外先读后写
        if (silent) {
            owner.stats.update().addAp(deltaAp).commitSilently();
        } else {
            owner.stats.update().addAp(deltaAp).commit();
        }
    }

    /** 单维分配：assignAttr(STR, x) 等 */
    boolean assignAttr(Stat s, int x) {
        Integer[] delta = new Integer[Stat.count()];
        delta[s.ordinal()] = x;
        return assignAttrs(delta);
    }

    /** 多维分配：delta[i] 为 null 的维不变；AP 余额或任一维 4..max_ap 校验失败返回 false */
    boolean assignAttrs(Integer[] delta) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            int apUsed = 0;
            int[] newAttrs = Arrays.copyOf(owner.stats.snapshot.base(), Stat.count());
            for (int i = SDIL_INDEX_BEGIN; i < SDIL_INDEX_END; i++) {
                if (delta[i] != null) {
                    apUsed += delta[i];
                    newAttrs[i] += delta[i];
                }
            }
            if (apUsed > getRemainingAp()) {
                return false;
            }

            int maxAp = GameConfig.getServerInt("max_ap");
            for (int i = SDIL_INDEX_BEGIN; i < SDIL_INDEX_END; i++) {
                if (newAttrs[i] < 4 || newAttrs[i] > maxAp) {
                    return false;
                }
            }

            owner.stats.update()
                    .set(STR, newAttrs[STR.ordinal()])
                    .set(DEX, newAttrs[DEX.ordinal()])
                    .set(INT, newAttrs[INT.ordinal()])
                    .set(LUK, newAttrs[LUK.ordinal()])
                    .addAp(-apUsed)   // 增量而非 set(getRemainingAp()-apUsed)：读旧值在事务内，原子
                    .commit();
            return true;
        }
    }

    boolean assignHP(int deltaHP, int deltaAp) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            if (!canSpendAp(deltaAp, owner.stats.getBase(MAX_HP) >= 30000)) {
                return false;
            }

            owner.stats.update()
                    .add(MAX_HP, deltaHP)
                    .addAp(-deltaAp)   // 增量而非 set(getRemainingAp()-deltaAp)：读旧值在事务内，原子
                    .commit();
            hpMpApUsed += deltaAp;
            return true;
        }
    }

    boolean assignMP(int deltaMP, int deltaAp) {
        try (var ignored = Locks.acquire(owner.stats.wLock)) {
            if (!canSpendAp(deltaAp, owner.stats.getBase(MAX_MP) >= 30000)) {
                return false;
            }

            owner.stats.update()
                    .add(MAX_MP, deltaMP)
                    .addAp(-deltaAp)   // 增量而非 set(getRemainingAp()-deltaAp)：读旧值在事务内，原子
                    .commit();
            hpMpApUsed += deltaAp;
            return true;
        }
    }

    // ── 持久化数据转换（ap 域；信封组装在 Character.toData/applyData） ──

    CharacterApData toData() {
        CharacterApData d = new CharacterApData();
        d.remainingAp = getRemainingAp();
        d.hpMpApUsed = hpMpApUsed;
        return d;
    }

    void applyData(CharacterApData d) {
        hpMpApUsed = d.hpMpApUsed;
        owner.stats.update().setAp(d.remainingAp).commitSilently();
    }

    private boolean canSpendAp(int deltaAp, boolean capReached) {
        return getRemainingAp() - deltaAp >= 0 && hpMpApUsed + deltaAp >= 0 && !capReached;
    }
}
