package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.constants.string.ExtendType;
import org.gms.dao.entity.ExtendValueDO;
import org.gms.net.server.world.World;
import org.gms.server.BuffEffectData;
import org.gms.server.ItemInformationProvider;
import org.gms.util.ExtendUtil;
import org.gms.util.Locks;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 倍率模块组件：玩家/世界 base 倍率（exp/meso/drop）+ 道具倍率贡献桶（coupon 等，见 doc/10）。
 * 数据 + 领域逻辑内聚于此，持有 owner 反向引用；Character 保留公开具名门面
 * （getExpRate/... 对外转发），并经 {@code Character.getRates()} 直接暴露本类（脚本 sink）。
 *
 * 边界：base 倍率与桶派生倍率在此汇聚（getter 相乘）；条目语义（哪张券几倍、何时生效）
 * 全部在道具脚本侧。
 */
public class CharacterRates {
    private final Character owner;

    /** base 倍率（世界 × 玩家，含 extend 覆盖）；倍率券贡献不在字段内，经 coupon 系（桶派生）在 getter 相乘 */
    private float expRate = 1;
    private float mesoRate = 1;
    private float dropRate = 1;
    /** 各 kind 的桶派生倍率（ITEM 桶 max，缺省 1）——由 {@link #recalc()} 唯一写入 */
    private int expCoupon = 1, mesoCoupon = 1, dropCoupon = 1;
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

        return expRate * expCoupon;
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
        return (dropRate * dropCoupon / w.getDropRate()) * w.getBossDropRate();
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

    public void resetPlayerRates() {
        expRate = 1;
        mesoRate = 1;
        dropRate = 1;
        // 桶条目（券贡献）不在此清理：GM/网页改倍率路径复用本方法重校 base，
        // 持有券的贡献应保持；桶随登出/道具离开自然撤销
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

    /** 调试/展示：当前桶内贡献物品 id 集（sourceId） */
    public Set<Integer> getActiveItemIds() {
        try (var ignored = Locks.acquire(owner.chrLock)) {
            return Collections.unmodifiableSet(collectContributingItemIds());
        }
    }

    private Set<Integer> collectContributingItemIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Item item : itemExp.keySet()) {
            ids.add(item.getItemId());
        }
        for (Item item : itemMeso.keySet()) {
            ids.add(item.getItemId());
        }
        for (Item item : itemDrop.keySet()) {
            ids.add(item.getItemId());
        }
        return ids;
    }

    // ── 倍率贡献桶（倍率 sink，脚本经 character.getRates() 调用；见 doc/10 分桶模型） ──
    //
    // ITEM 桶：条目 = 道具对象 → 绝对倍率（替换语义），按 kind 分表；桶值 = max(条目, 缺省 1)，
    // 由 recalc() 写入 coupon 系字段并在 getter 与 base 相乘。

    private final Map<Item, Integer> itemExp = new IdentityHashMap<>();
    private final Map<Item, Integer> itemMeso = new IdentityHashMap<>();
    private final Map<Item, Integer> itemDrop = new IdentityHashMap<>();
    /** 已应用 buff 图标的物品 sourceId 簿记（recalc 差量同步，替代旧 isRateCoupon 判定） */
    private Set<Integer> buffedItemIds = new LinkedHashSet<>();


    public void updateExp(RateBucket bucket, Item item, int multiplier) {
        requireItemBucket(bucket);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            itemExp.put(item, multiplier);
        }
    }


    public void updateMeso(RateBucket bucket, Item item, int multiplier) {
        requireItemBucket(bucket);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            itemMeso.put(item, multiplier);
        }
    }


    public void updateDrop(RateBucket bucket, Item item, int multiplier) {
        requireItemBucket(bucket);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            itemDrop.put(item, multiplier);
        }
    }


    public void withdrawExp(RateBucket bucket, Item item) {
        requireItemBucket(bucket);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            itemExp.remove(item);
        }
    }


    public void withdrawMeso(RateBucket bucket, Item item) {
        requireItemBucket(bucket);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            itemMeso.remove(item);
        }
    }


    public void withdrawDrop(RateBucket bucket, Item item) {
        requireItemBucket(bucket);
        try (var ignored = Locks.acquire(owner.chrLock)) {
            itemDrop.remove(item);
        }
    }


    public void recalc() {
        Set<Integer> contributing;
        try (var ignored = Locks.acquire(owner.chrLock)) {
            expCoupon = maxOf(itemExp);
            mesoCoupon = maxOf(itemMeso);
            dropCoupon = maxOf(itemDrop);
            contributing = collectContributingItemIds();
        }
        syncItemBuffs(contributing);
    }

    private static void requireItemBucket(RateBucket bucket) {
        if (bucket != RateBucket.ITEM) {
            throw new UnsupportedOperationException("暂只支持 ITEM 桶: " + bucket);
        }
    }

    private static int maxOf(Map<Item, Integer> entries) {
        int max = 1;
        for (int value : entries.values()) {
            max = Math.max(max, value);
        }
        return max;
    }

    /** buff 图标差量同步：贡献集缩小 → 撤退出的 sourceId buff；扩大 → 补应用新入的（展示策略在 commitBuffCoupon） */
    private void syncItemBuffs(Set<Integer> contributing) {
        Set<Integer> retired = new LinkedHashSet<>(buffedItemIds);
        retired.removeAll(contributing);
        if (!retired.isEmpty()) {
            for (EffectStatus effect : owner.buffs.getAllEffects()) {
                if (retired.contains(effect.getData().getSourceId())) {
                    owner.cancelEffect(effect.getData(), false);
                }
            }
        }
        for (Integer sourceId : contributing) {
            if (!buffedItemIds.contains(sourceId)) {
                commitBuffCoupon(sourceId);
            }
        }
        buffedItemIds = contributing;
    }

    /** 金币倍率（base × ITEM 桶派生） */
    float getMesoRate() {
        return mesoRate * mesoCoupon;
    }

    /** 掉落倍率（base × ITEM 桶派生） */
    float getDropRate() {
        return dropRate * dropCoupon;
    }
}
