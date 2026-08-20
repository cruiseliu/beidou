package org.gms.client.character;

import org.gms.client.Family;
import org.gms.client.FamilyEntry;
import org.gms.server.TimerManager;
import org.gms.util.PacketCreator;

import java.util.concurrent.ScheduledFuture;

/**
 * 家族模块组件：家族条目（familyEntry/familyId）+ 家族 buff（familyBuff/exp/drop + 定时器）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getFamily/setFamilyEntry/startFamilyBuffTimer/... 对外转发）。
 *
 * 边界：只承载家族语义——家族条目引用、家族 buff 状态与计时器。
 * 依赖经 owner 门面调用（sendPacket/...）。
 */
class CharacterFamily {
    private final Character owner;

    /** 家族条目（null 表示无家族） */
    private FamilyEntry familyEntry;

    /** 家族 id（家族条目派生，冗余保存） */
    private int familyId;

    /** 家族 buff 到期定时器 */
    private ScheduledFuture<?> FamilyBuffTimer = null;

    /** 是否启用家族 buff */
    private boolean familyBuff = false;

    // 获取 FamilyExp 的值
    private float familyExp = 1;
    private float familyDrop = 1;

    CharacterFamily(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    FamilyEntry getFamilyEntry() {
        return familyEntry;
    }

    void setFamilyEntry(FamilyEntry entry) {
        if (entry != null) {
            setFamilyId(entry.getFamily().getID());
        }
        this.familyEntry = entry;
    }

    int getFamilyId() {
        return familyId;
    }

    void setFamilyId(int familyId) {
        this.familyId = familyId;
    }

    Family getFamily() {
        if (familyEntry != null) {
            return familyEntry.getFamily();
        } else {
            return null;
        }
    }

    boolean isFamilyBuff() {
        return familyBuff;
    }

    float getFamilyExp() {
        return familyExp;
    }

    float getFamilyDrop() {
        return familyDrop;
    }

    // ── 家族 buff ──

    void setFamilyBuff(boolean type, float exp, float drop) {
        this.familyBuff = type;
        this.familyExp = exp;
        this.familyDrop = drop;
    }

    void startFamilyBuffTimer(int delay) {
        if (FamilyBuffTimer != null && !FamilyBuffTimer.isCancelled()) {
            FamilyBuffTimer.cancel(false);
        }
        FamilyBuffTimer = TimerManager.getInstance().schedule(() -> {
            try {
                owner.sendPacket(PacketCreator.cancelFamilyBuff());
            } finally {
                cancelFamilyBuffTimer();
            }
        }, delay);
    }

    void cancelFamilyBuffTimer() {
        if (FamilyBuffTimer != null && !FamilyBuffTimer.isCancelled()) {
            FamilyBuffTimer.cancel(false);
            setFamilyBuff(false, 1, 1);
        }
    }
}
