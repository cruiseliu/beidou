package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.Disease;
import org.gms.client.keybind.KeyBinding;
import org.gms.client.processor.action.PetAutopotProcessor;
import org.gms.manager.ServerManager;
import org.gms.service.HpMpAlertService;
import org.gms.client.Job;
import org.gms.client.Stat;
import org.gms.client.Skill;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.WeaponType;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.model.json.CharacterStatsData;
import org.gms.server.BuffEffectData;
import org.gms.server.ItemInformationProvider;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import org.gms.util.Randomizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 角色属性数据 + 纯计算 + 属性读写锁（rLock/wLock）+ HP/MP 变更编排与 local 重算。
 * 持锁路径直读字段更快；无外层锁时的单维读取走 getAttr。
 * 字段 package-private，同包的 Character 直接访问，不提供 getter/setter。
 * HP/MP 变更与 recalc 族方法持有 owner 反向引用，经 owner 门面发包/联动。
 */
public class CharacterStats {
    private final Character owner;

    private final ReadWriteLock statLock = new ReentrantReadWriteLock(true);
    final Lock rLock = statLock.readLock();
    final Lock wLock = statLock.writeLock();

    CharacterStats(Character owner) {
        this.owner = owner;
    }

    // ── 基础四维（下标见 BaseStat） ──
    final int[] attrs = new int[BaseStat.BASE_STAT_COUNT];

    // ── HP / MP ──
    int hp, mp;
    int maxHp, maxMp;
    int clientMaxHp, clientMaxMp;
    float transientHp = Float.NEGATIVE_INFINITY;
    float transientMp = Float.NEGATIVE_INFINITY;

    // ── local 系列（派生四维，recalc 后有效；下标见 BaseStat） ──
    final int[] localAttrs = new int[BaseStat.BASE_STAT_COUNT];
    int localmagic, localwatk;
    int localMaxHp = 50, localMaxMp = 5;

    // ── equip 系列（装备聚合中间四维，recalcEquipStats 的输出、local 计算的输入；下标见 BaseStat） ──
    final int[] equipAttrs = new int[BaseStat.BASE_STAT_COUNT];
    int equipmagic, equipwatk;
    int equipmaxhp, equipmaxmp;

    // ── 操作方法（package-private，由 Character 在持锁状态下调用） ──

    /** 带锁读取单维（无外层锁时使用；持锁路径直读 attrs 更快） */
    int getAttr(int idx) {
        try (var ignored = Locks.acquire(rLock)) {
            return attrs[idx];
        }
    }

    void setHp(int newHp) {
        int clamped = Math.clamp(newHp, 0, localMaxHp);
        if (hp != clamped) {
            transientHp = Float.NEGATIVE_INFINITY;
        }
        hp = clamped;
    }

    void setMp(int newMp) {
        int clamped = Math.clamp(newMp, 0, localMaxMp);
        if (mp != clamped) {
            transientMp = Float.NEGATIVE_INFINITY;
        }
        mp = clamped;
    }

    void setMaxHp(int newMaxHp) {
        if (maxHp < newMaxHp) {
            transientHp = Float.NEGATIVE_INFINITY;
        }
        maxHp = newMaxHp;
        clientMaxHp = Math.min(30000, newMaxHp);
    }

    void setMaxMp(int newMaxMp) {
        if (maxMp < newMaxMp) {
            transientMp = Float.NEGATIVE_INFINITY;
        }
        maxMp = newMaxMp;
        clientMaxMp = Math.min(30000, newMaxMp);
    }

    // ── 工具函数 ──

    static int getHpMpGainFromRange(int min, int max, boolean fixed) {
        return fixed ? (min + max) / 2 : Randomizer.rand(min, max);
    }

    static int calcTransientRatio(float transientpoint) {
        int ret = (int) transientpoint;
        return !(ret <= 0 && transientpoint > 0.0f) ? ret : 1;
    }

    // ── 升级/转职 基础 HP/MP 计算 ──

    Pair<Integer, Integer> getBasicLevelUpHpMp(Job job) {
        boolean fixedLevelUpHpMp = true;  // todo: [refactor] hard coded config
        int hp = 0, mp = 0;
        if (job.isBeginnerJob()) {
            hp = getHpMpGainFromRange(12, 16, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(10, 12, fixedLevelUpHpMp);
        } else if (job.isA(Job.WARRIOR) || job.isA(Job.DAWNWARRIOR1)) {
            hp = getHpMpGainFromRange(24, 28, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(4, 6, fixedLevelUpHpMp);
        } else if (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1)) {
            hp = getHpMpGainFromRange(10, 14, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(22, 24, fixedLevelUpHpMp);
        } else if (job.isA(Job.BOWMAN) || job.isA(Job.THIEF) || (job.getId() > 1299 && job.getId() < 1500)) {
            hp = getHpMpGainFromRange(20, 24, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(14, 16, fixedLevelUpHpMp);
        } else if (job.isA(Job.GM)) {
            hp = 30000;
            mp = 30000;
        } else if (job.isA(Job.PIRATE) || job.isA(Job.THUNDERBREAKER1)) {
            hp = getHpMpGainFromRange(22, 28, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(18, 23, fixedLevelUpHpMp);
        } else if (job.isA(Job.ARAN1)) {
            hp = getHpMpGainFromRange(44, 48, fixedLevelUpHpMp);
            mp = getHpMpGainFromRange(4, 8, fixedLevelUpHpMp);
            mp += (int) Math.floor(mp * 0.1);
        }
        return new Pair<>(hp, mp);
    }

    // ── HP/MP 比例计算（use_fixed_ratio_hpmp_update 启用时） ──

    int calcHpRatioUpdate(int curpoint, int maxpoint, int diffpoint) {
        int nextMax = Math.min(30000, maxpoint + diffpoint);
        float temp = curpoint * nextMax;
        int ret = (int) Math.ceil(temp / maxpoint);
        transientHp = (maxpoint > nextMax) ? ((float) curpoint) / maxpoint : ((float) ret) / nextMax;
        return ret;
    }

    int calcMpRatioUpdate(int curpoint, int maxpoint, int diffpoint) {
        int nextMax = Math.min(30000, maxpoint + diffpoint);
        float temp = curpoint * nextMax;
        int ret = (int) Math.ceil(temp / maxpoint);
        transientMp = (maxpoint > nextMax) ? ((float) curpoint) / maxpoint : ((float) ret) / nextMax;
        return ret;
    }

    int calcHpFromTransient() {
        return calcTransientRatio(transientHp * localMaxHp);
    }

    int calcMpFromTransient() {
        return calcTransientRatio(transientMp * localMaxMp);
    }

    // ── 装备属性聚合 ──

    /**
     * 将装备聚合值清零后累加指定装备列表的属性。
     * Character 负责传入 EQUIPPED 背包内容和管理 equipchanged 标志。
     */
    void aggregateEquipStats(Iterable<org.gms.client.inventory.Equip> equips) {
        equipmaxhp = 0;
        equipmaxmp = 0;
        for (int i = 0; i < BaseStat.BASE_STAT_COUNT; i++) {
            equipAttrs[i] = 0;
        }
        equipmagic = 0;
        equipwatk = 0;

        for (var eq : equips) {
            equipmaxhp += eq.getHp();
            equipmaxmp += eq.getMp();
            equipAttrs[BaseStat.DEX] += eq.getDex();
            equipAttrs[BaseStat.INT] += eq.getInt();
            equipAttrs[BaseStat.STR] += eq.getStr();
            equipAttrs[BaseStat.LUK] += eq.getLuk();
            equipmagic += eq.getMatk() + eq.getInt();
            equipwatk += eq.getWatk();
        }
    }

    /** 把 equip* 聚合值加到 local* 上（在 local* 已重置为基础值后调用）。 */
    void applyEquipToLocal() {
        localMaxHp += equipmaxhp;
        localMaxMp += equipmaxmp;
        for (int i = 0; i < BaseStat.BASE_STAT_COUNT; i++) {
            localAttrs[i] += equipAttrs[i];
        }
        localmagic += equipmagic;
        localwatk += equipwatk;
    }

    /** reapplyLocalStats 的第一步：把 local* 重置为基础属性值。 */
    void resetLocalToBase() {
        localMaxHp = maxHp;
        localMaxMp = maxMp;
        for (int i = 0; i < BaseStat.BASE_STAT_COUNT; i++) {
            localAttrs[i] = attrs[i];
        }
        localmagic = localAttrs[BaseStat.INT];
        localwatk = 0;
    }

    // ── 持久化数据转换（stats 域的映射；信封 CharacterData 的组装/应用在 Character.toData/applyData） ──

    CharacterStatsData toData() {
        CharacterStatsData d = new CharacterStatsData();
        d.str = attrs[BaseStat.STR];
        d.dex = attrs[BaseStat.DEX];
        d.int_ = attrs[BaseStat.INT];
        d.luk = attrs[BaseStat.LUK];
        d.hp = hp;
        d.mp = mp;
        d.maxHp = maxHp;
        d.maxMp = maxMp;
        return d;
    }

    void applyData(CharacterStatsData d) {
        attrs[BaseStat.STR] = d.str;
        attrs[BaseStat.DEX] = d.dex;
        attrs[BaseStat.INT] = d.int_;
        attrs[BaseStat.LUK] = d.luk;
        hp = d.hp;
        mp = d.mp;
        maxHp = d.maxHp;
        maxMp = d.maxMp;
        clientMaxHp = Math.min(30000, maxHp);
        clientMaxMp = Math.min(30000, maxMp);
    }

    // ══════════════════ HP/MP 变更与 local 重算（迁移自 Character） ══════════════════

    // ── HP/MP 变更 ──

    private void setHpInternal(int newHp) {
        int oldHp = hp;
        setHp(newHp);
        owner.hpChangeAction(oldHp);
    }

    public int safeAddHP(int delta) {
        try (var ignored = Locks.acquire(wLock)) {
            if (hp + delta <= 0) {
                delta = -hp + 1;
            }
            addHP(delta);
            return delta;
        }
    }

    public void addHP(int delta) {
        try (var ignored = Locks.acquire(wLock)) {
            applyUpdate(new StatsUpdate().setHp(hp + delta));
        }
    }

    public void addMP(int delta) {
        try (var ignored = Locks.acquire(wLock)) {
            applyUpdate(new StatsUpdate().setMp(mp + delta));
        }
    }

    public void addMPHP(int hpDelta, int mpDelta) {
        try (var ignored = Locks.acquire(wLock)) {
            applyUpdate(new StatsUpdate().setHp(hp + hpDelta).setMp(mp + mpDelta));
        }
    }

    void addMaxMPMaxHP(int hpdelta, int mpdelta, boolean silent) {
        try (var ignored = Locks.acquire(wLock)) {
            StatsUpdate u = new StatsUpdate().setMaxHp(maxHp + hpdelta).setMaxMp(maxMp + mpdelta);
            if (silent) {
                applyUpdateSilently(u);
            } else {
                applyUpdate(u);
            }
        }
    }

    public void addMaxHP(int delta) {
        try (var ignored = Locks.acquire(wLock)) {
            applyUpdate(new StatsUpdate().setMaxHp(maxHp + delta));
        }
    }

    public void addMaxMP(int delta) {
        try (var ignored = Locks.acquire(wLock)) {
            applyUpdate(new StatsUpdate().setMaxMp(maxMp + delta));
        }
    }

    void enforceMaxHpMp() {
        try (var ignored = Locks.acquire(wLock)) {
            if (mp > localMaxMp || hp > localMaxHp) {
                applyUpdate(new StatsUpdate().setHp(hp).setMp(mp));
            }
        }
    }

    // ── 属性更新（发包/静默） ──

    /** 应用属性更新（发包通知客户端），返回本次变更集 */
    Map<Stat, Integer> applyUpdate(StatsUpdate u) {
        Map<Stat, Integer> statUpdates = applyUpdateSilently(u);
        if (!statUpdates.isEmpty()) {
            announceStatsUpdate(statUpdates);
        }
        return statUpdates;
    }

    /** 应用属性更新并返回本次变更集（不发包） */
    Map<Stat, Integer> applyUpdateSilently(StatsUpdate u) {
        try (var ignored = Locks.acquire(wLock)) {
            Map<Stat, Integer> statUpdates = new HashMap<>();
            boolean poolUpdate = false;
            boolean statUpdate = false;

            if (u.hp != null || u.mp != null || u.maxHp != null || u.maxMp != null) {
                if (u.maxHp != null) {
                    poolUpdate = true;
                    setMaxHp(Math.max(50, u.maxHp));
                    statUpdates.put(Stat.MAXHP, clientMaxHp);
                    statUpdates.put(Stat.HP, hp);
                }

                if (u.hp != null) {
                    setHpInternal(u.hp);
                    statUpdates.put(Stat.HP, hp);
                }

                if (u.maxMp != null) {
                    poolUpdate = true;
                    setMaxMp(Math.max(5, u.maxMp));
                    statUpdates.put(Stat.MAXMP, clientMaxMp);
                    statUpdates.put(Stat.MP, mp);
                }

                if (u.mp != null) {
                    setMp(u.mp);
                    statUpdates.put(Stat.MP, mp);
                }
            }

            boolean basePresent = false;
            for (int i = 0; i < BaseStat.BASE_STAT_COUNT; i++) {
                Integer v = u.attrs[i];
                if (v == null) {
                    continue;
                }
                basePresent = true;
                if (v >= 4) {   // 四维下限：低于 4 的写入被跳过
                    attrs[i] = v;
                    statUpdates.put(BaseStat.KEYS[i], v);
                }
            }

            boolean apPresent = u.ap != null && u.ap >= 0;
            if (apPresent) {
                owner.ap.remainingAp = u.ap;
                statUpdates.put(Stat.AVAILABLEAP, owner.ap.remainingAp);
            }

            if (basePresent || apPresent) {
                statUpdate = true;
            }

            if (!statUpdates.isEmpty()) {
                if (poolUpdate) {
                    statUpdates.putAll(onHpMpPoolUpdate());
                }

                if (statUpdate) {
                    recalcLocalStats();
                }
            }
            return statUpdates;
        }
    }

    // ── HP/MP 池更新 ──

    /** HP/MP 池更新后的重算与钳制，返回需并入本次公告的属性修正 */
    private Map<Stat, Integer> onHpMpPoolUpdate() {
        Map<Stat, Integer> updates = new HashMap<>();
        List<Pair<Stat, Integer>> hpmpupdate = recalcLocalStats();
        for (Pair<Stat, Integer> p : hpmpupdate) {
            updates.put(p.getLeft(), p.getRight());
        }

        if (hp > localMaxHp) {
            setHp(localMaxHp);
            updates.put(Stat.HP, hp);
        }

        if (mp > localMaxMp) {
            setMp(localMaxMp);
            updates.put(Stat.MP, mp);
        }
        return updates;
    }

    // ── local 重算（reapply/recalc/updateLocalStats） ──

    private void recalcEquipStats() {
        if (owner.isEquipChanged()) {
            java.util.List<org.gms.client.inventory.Equip> equippedList = new java.util.ArrayList<>();
            for (Item item : owner.getInventory(InventoryType.EQUIPPED)) {
                equippedList.add((org.gms.client.inventory.Equip) item);
            }
            aggregateEquipStats(equippedList);
            owner.setEquipChanged(false);
        }
        applyEquipToLocal();
    }

    public void reapplyLocalStats() {
        try (var ignored = Locks.acquire(wLock)) {
            resetLocalToBase();

            recalcEquipStats();

            localmagic = Math.min(localmagic, 2000);

            Integer hbhp = owner.getBuffedValue(EffectType.HYPERBODYHP);
            if (hbhp != null) {
                localMaxHp += (int) ((hbhp.doubleValue() / 100) * localMaxHp);
            }
            Integer hbmp = owner.getBuffedValue(EffectType.HYPERBODYMP);
            if (hbmp != null) {
                localMaxMp += (int) ((hbmp.doubleValue() / 100) * localMaxMp);
            }

            localMaxHp = Math.min(30000, localMaxHp);
            localMaxMp = Math.min(30000, localMaxMp);

            BuffEffectData combo = owner.getBuffEffect(EffectType.ARAN_COMBO);
            if (combo != null) {
                localwatk += combo.getX();
            }

            if (owner.getEnergyBar() == 15000) {
                Skill energycharge = owner.isCygnus() ? SkillFactory.getSkill(ThunderBreaker.ENERGY_CHARGE) : SkillFactory.getSkill(Marauder.ENERGY_CHARGE);
                BuffEffectData ceffect = energycharge.getEffect(owner.getSkillLevel(energycharge));
                localwatk += ceffect.getWatk();
            }

            Integer mwarr = owner.getBuffedValue(EffectType.MAPLE_WARRIOR);
            if (mwarr != null) {
                localAttrs[BaseStat.STR] += owner.getStr() * mwarr / 100;
                localAttrs[BaseStat.DEX] += owner.getDex() * mwarr / 100;
                localAttrs[BaseStat.INT] += owner.getInt() * mwarr / 100;
                localAttrs[BaseStat.LUK] += owner.getLuk() * mwarr / 100;
            }
            if (owner.getJob().isA(Job.BOWMAN)) {
                Skill expert = null;
                if (owner.getJob().isA(Job.MARKSMAN)) {
                    expert = SkillFactory.getSkill(3220004);
                } else if (owner.getJob().isA(Job.BOWMASTER)) {
                    expert = SkillFactory.getSkill(3120005);
                }
                if (expert != null) {
                    int boostLevel = owner.getSkillLevel(expert);
                    if (boostLevel > 0) {
                        localwatk += expert.getEffect(boostLevel).getX();
                    }
                }
            }

            Integer watkbuff = owner.getBuffedValue(EffectType.WATK);
            if (watkbuff != null) {
                localwatk += watkbuff;
            }
            Integer matkbuff = owner.getBuffedValue(EffectType.MATK);
            if (matkbuff != null) {
                localmagic += matkbuff;
            }

            int blessing = owner.getSkillLevel(10000000 * owner.getJobType() + 12);
            if (blessing > 0) {
                localwatk += blessing;
                localmagic += blessing * 2;
            }

            if (owner.getJob().isA(Job.THIEF) || owner.getJob().isA(Job.BOWMAN) || owner.getJob().isA(Job.PIRATE) || owner.getJob().isA(Job.NIGHTWALKER1) || owner.getJob().isA(Job.WINDARCHER1)) {
                Item weapon_item = owner.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
                if (weapon_item != null) {
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    WeaponType weapon = ii.getWeaponType(weapon_item.getItemId());
                    boolean bow = weapon == WeaponType.BOW;
                    boolean crossbow = weapon == WeaponType.CROSSBOW;
                    boolean claw = weapon == WeaponType.CLAW;
                    boolean gun = weapon == WeaponType.GUN;
                    if (bow || crossbow || claw || gun) {
                        // Also calc stars into this.
                        Inventory inv = owner.getInventory(InventoryType.USE);
                        for (short i = 1; i <= inv.getSlotLimit(); i++) {
                            Item item = inv.getItem(i);
                            if (item == null) {
                                continue;
                            }
                            if ((claw && ItemConstants.isThrowingStar(item.getItemId()))
                                    || (gun && ItemConstants.isBullet(item.getItemId()))
                                    || (bow && ItemConstants.isArrowForBow(item.getItemId()))
                                    || (crossbow && ItemConstants.isArrowForCrossBow(item.getItemId()))) {
                                if (item.getQuantity() > 0) {
                                    // Finally there!
                                    localwatk += ii.getWatkForProjectile(item.getItemId());
                                    break;
                                }
                            }
                        }
                    }
                }
                // Add throwing stars to dmg.
            }

            owner.chair.invalidateHealStats();    // 装备/属性变化后椅子恢复参数需重算
        }
    }

    public List<Pair<Stat, Integer>> recalcLocalStats() {
        try (var ignored = Locks.acquire(wLock)) {
            List<Pair<Stat, Integer>> hpmpupdate = new ArrayList<>(2);
            int oldlocalmaxhp = localMaxHp;
            int oldlocalmaxmp = localMaxMp;

            reapplyLocalStats();

            if (GameConfig.getServerBoolean("use_fixed_ratio_hpmp_update")) {
                if (localMaxHp != oldlocalmaxhp) {
                    Pair<Stat, Integer> hpUpdate;

                    if (transientHp == Float.NEGATIVE_INFINITY) {
                        hpUpdate = calcHpRatioUpdate(localMaxHp, oldlocalmaxhp);
                    } else {
                        hpUpdate = calcHpRatioTransient();
                    }

                    hpmpupdate.add(hpUpdate);
                }

                if (localMaxMp != oldlocalmaxmp) {
                    Pair<Stat, Integer> mpUpdate;

                    if (transientMp == Float.NEGATIVE_INFINITY) {
                        mpUpdate = calcMpRatioUpdate(localMaxMp, oldlocalmaxmp);
                    } else {
                        mpUpdate = calcMpRatioTransient();
                    }

                    hpmpupdate.add(mpUpdate);
                }
            }

            return hpmpupdate;
        }
    }

    void updateLocalStats() {
        owner.prtLock.lock();
        try (var ignored = Locks.acquire(wLock)) {
            int oldmaxhp = localMaxHp;
            List<Pair<Stat, Integer>> hpmpupdate = recalcLocalStats();
            enforceMaxHpMp();

            if (!hpmpupdate.isEmpty()) {
                owner.sendPacket(PacketCreator.updatePlayerStats(hpmpupdate, true, owner));
            }

            if (oldmaxhp != localMaxHp) {   // thanks Wh1SK3Y (Suwaidy) for pointing out a deadlock occuring related to party members HP
                owner.updatePartyMemberHP();
            }
        } finally {
            owner.prtLock.unlock();
        }
    }

    // ── HP/MP 比例计算编排 ──

    private Pair<Stat, Integer> calcHpRatioUpdate(int newHp, int oldHp) {
        int delta = newHp - oldHp;
        hp = calcHpRatioUpdate(hp, oldHp, delta);
        owner.hpChangeAction(Short.MIN_VALUE);
        return new Pair<>(Stat.HP, hp);
    }

    private Pair<Stat, Integer> calcMpRatioUpdate(int newMp, int oldMp) {
        int delta = newMp - oldMp;
        mp = calcMpRatioUpdate(mp, oldMp, delta);
        return new Pair<>(Stat.MP, mp);
    }

    private Pair<Stat, Integer> calcHpRatioTransient() {
        hp = calcHpFromTransient();
        owner.hpChangeAction(Short.MIN_VALUE);
        return new Pair<>(Stat.HP, hp);
    }

    private Pair<Stat, Integer> calcMpRatioTransient() {
        mp = calcMpFromTransient();
        return new Pair<>(Stat.MP, mp);
    }

    // ══════════════════ HP/MP 变更编排（announce/hpChange/applyHpMpChange） ══════════════════

    /** 公告属性变更（发包通知客户端） */
    void announceStatsUpdate(Map<Stat, Integer> statUpdates) {
        List<Pair<Stat, Integer>> statup = new ArrayList<>(statUpdates.size());
        for (Map.Entry<Stat, Integer> s : statUpdates.entrySet()) {
            statup.add(new Pair<>(s.getKey(), s.getValue()));
        }

        owner.sendPacket(PacketCreator.updatePlayerStats(statup, true, owner));
    }

    /** HP 变化联动：死亡判定 + 队伍 HP 同步 + 狂暴检查 */
    void hpChangeAction(int oldHp) {
        boolean playerDied = false;
        if (hp <= 0) {
            if (oldHp > hp) {
                playerDied = true;
            }
        }

        final boolean chrDied = playerDied;
        Runnable r = () -> {
            owner.updatePartyMemberHP();    // thanks BHB (BHB88) for detecting a deadlock case within player stats.

            if (chrDied) {
                owner.playerDead();
            } else {
                owner.checkBerserk(owner.isHidden());
            }
        };
        if (owner.getMap() != null) {
            owner.getMap().registerCharacterStatUpdate(r);
        }
    }

    /** HP/MP 变更（含自动药水触发与 GM 保护），返回是否成功应用 */
    public boolean applyHpMpChange(int hpCon, int hpchange, int mpchange) {
        boolean zombify = owner.hasDisease(Disease.ZOMBIFY);

        // effLock 已冗余：块内仅 updateHpMp；zombify 检查在加锁前
        try (var ignored = Locks.acquire(wLock)) {
            int nextHp = hp + hpchange, nextMp = mp + mpchange;
            boolean cannotApplyHp = hpchange != 0 && nextHp <= 0 && (!zombify || hpCon > 0);
            boolean cannotApplyMp = mpchange != 0 && nextMp < 0;

            if (cannotApplyHp || cannotApplyMp) {
                if (!owner.isGM()) {
                    return false;
                }

                if (cannotApplyHp) {
                    nextHp = 1;
                }
            }

            applyUpdate(new StatsUpdate().setHp(nextHp).setMp(nextMp));
        }

        if (GameConfig.getServerBoolean("use_server_auto_pot") || GameConfig.getServerBoolean("use_compulsory_auto_pot")) {
            float autoHpAlert, autoMpAlert;
            if (GameConfig.getServerBoolean("use_server_auto_pot")) {
                autoHpAlert = Character.hpMpAlertService.getHpAlertPer(owner.id);
                autoMpAlert = Character.hpMpAlertService.getMpAlertPer(owner.id);
            } else {
                autoHpAlert = (float) GameConfig.getServerFloat("pet_auto_hp_ratio");
                autoMpAlert = (float) GameConfig.getServerFloat("pet_auto_mp_ratio");
            }

            if (hpchange < 0) {
                KeyBinding autoHpPot = owner.getKeymap().get(91);
                if (autoHpPot != null) {
                    int autoHpItemId = autoHpPot.getAction();
                    if (((float) owner.getHp()) / owner.getCurrentMaxHp() <= autoHpAlert) {
                        Item autoHpItem = owner.getInventory(InventoryType.USE).findById(autoHpItemId);
                        if (autoHpItem != null) {
                            PetAutopotProcessor.runAutopotAction(owner.client, autoHpItem.getPosition(), autoHpItemId);
                        }
                    }
                }
            }

            if (mpchange < 0) {
                KeyBinding autoMpPot = owner.getKeymap().get(92);
                if (autoMpPot != null) {
                    int autoMpItemId = autoMpPot.getAction();
                    if (((float) owner.getMp()) / owner.getCurrentMaxMp() <= autoMpAlert) {
                        Item autoMpItem = owner.getInventory(InventoryType.USE).findById(autoMpItemId);
                        if (autoMpItem != null) {
                            PetAutopotProcessor.runAutopotAction(owner.client, autoMpItem.getPosition(), autoMpItemId);
                        }
                    }
                }
            }
        } else {
            if (hpchange < 0) {
                owner.sendPacket(PacketCreator.onNotifyHPDecByField(hpchange * -1));
            }
        }

        return true;
    }
}
