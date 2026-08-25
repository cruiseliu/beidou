package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.string.ExtendType;
import org.gms.dao.entity.ExtendValueDO;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;
import org.gms.server.BuffEffectData;
import org.gms.server.ItemInformationProvider;
import org.gms.util.ExtendUtil;
import org.gms.util.Locks;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * 倍率与优惠券模块组件：玩家/世界倍率（exp/meso/drop）+ 经验优惠券（coupon）管理与叠加。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getExpRate/getCouponRates/updateCouponRates/... 对外转发）。
 *
 * 边界：只承载倍率与优惠券语义。原实现分散在 Character 的 getExpRate 系列与 setCouponRates 系列，
 * 重构收敛为本类；优惠券 buff 的施加/解除复用 owner 的 buff 门面（cancelEffect/applyTo）。
 */
class CharacterRates {
    private final Character owner;

    private float expRate = 1;
    private float mesoRate = 1;
    private float dropRate = 1;
    private int expCoupon = 1, mesoCoupon = 1, dropCoupon = 1;
    private final Map<Integer, Integer> activeCoupons = new LinkedHashMap<>();
    private final Map<Integer, Integer> activeCouponRates = new LinkedHashMap<>();
    private float mobExpRate = -1;

    CharacterRates(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    public boolean hasNoviceExpRate() {
        return GameConfig.getServerBoolean("use_enforce_novice_exp_rate") && owner.isBeginnerJob() && owner.getLevel() < 11;
    }

    public float getExpRate() {
        if (hasNoviceExpRate()) {   // base exp rate 1x for early levels idea thanks to Vcoc
            return 1;
        }

        return expRate;
    }

    public float getLevelExpRate() {
        if (hasNoviceExpRate()) return 1; // 新手经验保护

        return 1f + GameConfig.getWorldFloat(owner.getWorld(), "level_exp_rate") * owner.getLevel();
    }

    public float getQuickLevelExpRate() {
        if (hasNoviceExpRate()) return 1; // 新手经验保护

        int quickLv = GameConfig.getWorldInt(owner.getWorld(), "quick_level");
        if (owner.getLevel() >= quickLv) return 1;

        return 1f + (quickLv - owner.getLevel()) * GameConfig.getWorldFloat(owner.getWorld(), "quick_level_exp_rate");
    }

    public void updateMobExpRate() {
        mobExpRate = getLevelExpRate() * getQuickLevelExpRate();
    }

    public float getMobExpRate() {
        if (mobExpRate <= 0) updateMobExpRate();
        return mobExpRate;
    }

    public int getCouponExpRate() {
        return expCoupon;
    }

    public float getRawExpRate() {
        return expRate / (expCoupon * owner.getWorldServer().getExpRate());
    }

    public int getCouponDropRate() {
        return dropCoupon;
    }

    public float getRawDropRate() {
        return dropRate / (dropCoupon * owner.getWorldServer().getDropRate());
    }

    public float getBossDropRate() {
        World w = owner.getWorldServer();
        return (dropRate / w.getDropRate()) * w.getBossDropRate();
    }

    public int getCouponMesoRate() {
        return mesoCoupon;
    }

    public float getRawMesoRate() {
        return mesoRate / (mesoCoupon * owner.getWorldServer().getMesoRate());
    }

    public float getQuestExpRate() {
        if (hasNoviceExpRate()) {
            return 1;
        }

        World w = owner.getWorldServer();
        return w.getExpRate() * w.getQuestRate();
    }

    public float getQuestMesoRate() {
        World w = owner.getWorldServer();
        return w.getMesoRate() * w.getQuestRate();
    }

    public float getCardRate(int itemid) {
        float rate = 100.0f;

        if (itemid == 0) {
            BuffEffectData mseMeso = owner.getBuffEffect(EffectType.MESO_UP_BY_ITEM);
            if (mseMeso != null) {
                rate += mseMeso.getCardRate(owner.getMapId(), itemid);
            }
        } else {
            BuffEffectData mseItem = owner.getBuffEffect(EffectType.ITEM_UP_BY_ITEM);
            if (mseItem != null) {
                rate += mseItem.getCardRate(owner.getMapId(), itemid);
            }
        }

        return rate / 100;
    }

    // ── 倍率设置/重置（升级加成 + 世界倍率） ──

    public void setPlayerRates() {
        applySavedRateOrElse("expRate", () -> this.expRate *= GameConstants.getPlayerBonusExpRate(owner.getLevel() / 20));
        applySavedRateOrElse("mesoRate", () -> this.mesoRate *= GameConstants.getPlayerBonusMesoRate(owner.getLevel() / 20));
        applySavedRateOrElse("dropRate", () -> this.dropRate *= GameConstants.getPlayerBonusDropRate(owner.getLevel() / 20));
    }

    public void revertLastPlayerRates() {
        this.expRate /= GameConstants.getPlayerBonusExpRate((owner.getLevel() - 1) / 20);
        this.mesoRate /= GameConstants.getPlayerBonusMesoRate((owner.getLevel() - 1) / 20);
        this.dropRate /= GameConstants.getPlayerBonusDropRate((owner.getLevel() - 1) / 20);
    }

    public void revertPlayerRates() {
        this.expRate /= GameConstants.getPlayerBonusExpRate(owner.getLevel() / 20);
        this.mesoRate /= GameConstants.getPlayerBonusMesoRate(owner.getLevel() / 20);
        this.dropRate /= GameConstants.getPlayerBonusDropRate(owner.getLevel() / 20);
    }

    public void setWorldRates() {
        World worldz = owner.getWorldServer();
        applySavedRateOrElse("expRate", () -> this.expRate *= worldz.getExpRate());
        applySavedRateOrElse("mesoRate", () -> this.mesoRate *= worldz.getMesoRate());
        applySavedRateOrElse("dropRate", () -> this.dropRate *= worldz.getDropRate());
    }

    public void revertWorldRates() {
        World worldz = owner.getWorldServer();
        this.expRate /= worldz.getExpRate();
        this.mesoRate /= worldz.getMesoRate();
        this.dropRate /= worldz.getDropRate();
    }

    private void applySavedRateOrElse(String type, Runnable runnable) {
        ExtendValueDO extendValueDO = ExtendUtil.getExtendValue(String.valueOf(owner.getId()), ExtendType.CHARACTER_EXTEND.getType(), type);

        if (extendValueDO == null) {
            runnable.run();
            return;
        }
        float savedRateValue = Float.parseFloat(extendValueDO.getExtendValue());
        switch (type) {
            case "expRate" -> this.expRate = savedRateValue;
            case "mesoRate" -> this.mesoRate = savedRateValue;
            case "dropRate" -> this.dropRate = savedRateValue;
        }
    }

    // ── 优惠券（coupon） ──

    public void setCouponRates() {
        List<Integer> couponEffects;

        Collection<ItemSlot> cashItems = owner.getInventory(InventoryType.CASH).list();
        try (var ignored = Locks.acquire(owner.chrLock)) {
            setActiveCoupons(cashItems);
            couponEffects = activateCouponsEffects();
        }

        for (Integer couponId : couponEffects) {
            commitBuffCoupon(couponId);
        }
    }

    private void revertCouponRates() {
        revertCouponsEffects();
    }

    public void updateCouponRates() {
        Inventory cashInv = owner.getInventory(InventoryType.CASH);
        if (cashInv == null) {
            return;
        }

        // effLock/chrLock 已冗余：revert/setCouponRates 内部自持锁

        cashInv.lockInventory();
        try {
            revertCouponRates();
            setCouponRates();
        } finally {
            cashInv.unlockInventory();

        }
    }

    public void resetPlayerRates() {
        expRate = 1;
        mesoRate = 1;
        dropRate = 1;

        expCoupon = 1;
        mesoCoupon = 1;
        dropCoupon = 1;
    }

    private int getCouponMultiplier(int couponId) {
        return activeCouponRates.get(couponId);
    }

    private void setExpCouponRate(int couponId, int couponQty) {
        this.expCoupon *= (getCouponMultiplier(couponId) * couponQty);
    }

    private void setDropCouponRate(int couponId, int couponQty) {
        this.dropCoupon *= (getCouponMultiplier(couponId) * couponQty);
        this.mesoCoupon *= (getCouponMultiplier(couponId) * couponQty);
    }

    private void revertCouponsEffects() {
        dispelBuffCoupons();

        this.expRate /= this.expCoupon;
        this.dropRate /= this.dropCoupon;
        this.mesoRate /= this.mesoCoupon;

        this.expCoupon = 1;
        this.dropCoupon = 1;
        this.mesoCoupon = 1;
    }

    private List<Integer> activateCouponsEffects() {
        List<Integer> toCommitEffect = new LinkedList<>();

        if (GameConfig.getServerBoolean("use_stack_coupon_rates")) {
            for (Entry<Integer, Integer> coupon : activeCoupons.entrySet()) {
                int couponId = coupon.getKey();
                int couponQty = coupon.getValue();

                toCommitEffect.add(couponId);

                if (ItemConstants.isExpCoupon(couponId)) {
                    setExpCouponRate(couponId, couponQty);
                } else {
                    setDropCouponRate(couponId, couponQty);
                }
            }
        } else {
            int maxExpRate = 1, maxDropRate = 1, maxExpCouponId = -1, maxDropCouponId = -1;

            for (Entry<Integer, Integer> coupon : activeCoupons.entrySet()) {
                int couponId = coupon.getKey();

                if (ItemConstants.isExpCoupon(couponId)) {
                    if (maxExpRate < getCouponMultiplier(couponId)) {
                        maxExpCouponId = couponId;
                        maxExpRate = getCouponMultiplier(couponId);
                    }
                } else {
                    if (maxDropRate < getCouponMultiplier(couponId)) {
                        maxDropCouponId = couponId;
                        maxDropRate = getCouponMultiplier(couponId);
                    }
                }
            }

            if (maxExpCouponId > -1) {
                toCommitEffect.add(maxExpCouponId);
            }
            if (maxDropCouponId > -1) {
                toCommitEffect.add(maxDropCouponId);
            }

            this.expCoupon = maxExpRate;
            this.dropCoupon = maxDropRate;
            this.mesoCoupon = maxDropRate;
        }

        this.expRate *= this.expCoupon;
        this.dropRate *= this.dropCoupon;
        this.mesoRate *= this.mesoCoupon;

        return toCommitEffect;
    }

    private void setActiveCoupons(Collection<ItemSlot> cashItems) {
        activeCoupons.clear();
        activeCouponRates.clear();

        Map<Integer, Integer> coupons = Server.getInstance().getCouponRates();
        List<Integer> active = Server.getInstance().getActiveCoupons();

        for (ItemSlot it : cashItems) {
            if (ItemConstants.isRateCoupon(it.getItemId()) && active.contains(it.getItemId())) {
                Integer count = activeCoupons.get(it.getItemId());

                if (count != null) {
                    activeCoupons.put(it.getItemId(), count + 1);
                } else {
                    activeCoupons.put(it.getItemId(), 1);
                    activeCouponRates.put(it.getItemId(), coupons.get(it.getItemId()));
                }
            }
        }
    }

    private void commitBuffCoupon(int couponid) {
        if (!GameConfig.getServerBoolean("show_coupon_buff")) {
            return;
        }
        if (!owner.isLoggedIn() || owner.getCashShop().isOpened()) {
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        BuffEffectData mse = ii.getItemEffect(couponid);
        mse.applyTo(owner);
    }

    public void dispelBuffCoupons() {
        List<EffectStatus> effects = owner.buffs.getAllEffects();

        for (EffectStatus effect : effects) {
            if (ItemConstants.isRateCoupon(effect.getData().getSourceId())) {
                owner.cancelEffect(effect.getData(), false);
            }
        }
    }

    public Set<Integer> getActiveCoupons() {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            return Collections.unmodifiableSet(activeCoupons.keySet());
        }
    }

    /** 内部倍率字段（expRate/mesoRate/dropRate）直接访问门面，供持久化/重置等场景 */
    float getMesoRate() {
        return mesoRate;
    }

    float getDropRate() {
        return dropRate;
    }
}
