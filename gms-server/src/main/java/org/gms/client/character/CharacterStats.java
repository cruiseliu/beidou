package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.Disease;
import org.gms.client.keybind.KeyBinding;
import org.gms.client.processor.action.PetAutopotProcessor;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.weaponType.WeaponTypeDefinition;
import org.gms.client.weaponType.WeaponTypeRegistry;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.model.json.CharacterStatsData;

import org.gms.remote.RemoteUpdate;
import org.gms.remote.StatsUpdate;
import org.gms.server.BuffEffectData;
import org.gms.server.ItemInformationProvider;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 角色属性数据：update transaction + volatile snapshot 模型。
 * <p>
 * 所有状态收敛到 {@link StatsSnapshot}base/local/hp/mp/ap），volatile 发布；
 * 写路径持 wLock（单写者）经 {@link #updateInternal(boolean, Change...)} 事务重建快照（copy-on-write，未变数组按引用复用），
 * 读路径无锁读 snapshot 引用——永远看到某个已完成事务的一致视图（与 ActiveBuffs 的 volatile effects 同思路）。
 * <p>
 * base/local 数组语义上不可改（caller guarantee：写路径新建数组，旧数组发布后不再修改）。
 */
public class CharacterStats {
    private final Character owner;

    private final ReadWriteLock statLock = new ReentrantReadWriteLock(true);
    final Lock rLock = statLock.readLock();
    final Lock wLock = statLock.writeLock();

    volatile StatsSnapshot snapshot = StatsSnapshot.initial();

    CharacterStats(Character owner) {
        this.owner = owner;
    }

    // ── 读路径（无锁 volatile） ──

    int getBase(Stat stat) {
        return snapshot.base()[stat.ordinal()];
    }

    int getTotal(Stat stat) {
        return snapshot.total()[stat.ordinal()];
    }

    int getHp() {
        return snapshot.hp();
    }

    int getMp() {
        return snapshot.mp();
    }

    // ── 写事务（单写者：持 wLock 重建快照并 volatile 发布） ──

    /**
     * 属性更新语法糖入口：stats.update().set(STR, x).add(MAX_HP, y).commit()。
     * builder 链在锁外构建（commit 才持 wLock），禁止先读后写（相对修改用 add 增量，见 StatUpdateBuilder）。
     */
    StatUpdateBuilder update() {
        return new StatUpdateBuilder(this);
    }

    /**
     * 重算 total stats（API 层面独立调用，非 Change 变更）：装备/buff 变化后触发，
     * 直接计算并发布新快照（不进入 update 事务的变更折叠）。
     */
    void recalc() {
        try (var ignored = Locks.acquire(wLock)) {
            StatsSnapshot old = snapshot;
            int[] newTotal = computeTotal(old.base());
            int hp = Math.clamp(old.hp(), 0, newTotal[Stat.MAX_HP.ordinal()]);
            int mp = Math.clamp(old.mp(), 0, newTotal[Stat.MAX_MP.ordinal()]);
            snapshot = new StatsSnapshot(old.base(), newTotal, hp, mp, old.ap());
        }
    }

    /** 重算 total stats 并在 maxHp 变化时同步队伍成员 HP（buff 变化/换装后的联动） */
    void recalcAndSyncParty() {
        try (var ignored = Locks.acquire(owner.party.lock, wLock)) {
            int oldmaxhp = snapshot.total()[Stat.MAX_HP.ordinal()];
            recalc();
            if (oldmaxhp != snapshot.total()[Stat.MAX_HP.ordinal()]) {   // thanks Wh1SK3Y (Suwaidy) for pointing out a deadlock occuring related to party members HP
                owner.updatePartyMemberHP();
            }
        }
    }

    /** 受击扣血钳制（HP 不低于 1）：非纯一行式，保留；原子边界由 update()...commit() 显式表达 */
    int safeAddHP(int delta) {
        try (var ignored = Locks.acquire(wLock)) {
            if (snapshot.hp() + delta <= 0) {
                delta = 1 - snapshot.hp();
            }
            update().addHp(delta).commit();
            return delta;
        }
    }

    // ── private methods ──

    /** 应用变更并立即发包通知客户端（updateStats + 解锁 + commit 一个事务） */
    void updateInternal(boolean silent, Change... changes) {
        StatsUpdate statUpdates;
        try (var ignored = Locks.acquire(wLock)) {
            StatsSnapshot old = snapshot;
            snapshot = applyChanges(old, changes);
            statUpdates = diffStats(old, snapshot);
            if (old.hp() != snapshot.hp()) {
                hpChangeAction(old.hp());
            }
        }
        if (!silent && !statUpdates.isEmpty()) {
            owner.remote().update().updateStats(statUpdates).unlockActions().commit();
        }
    }

    /** 应用变更并写入外部事务（调用方负责后续 updateSp 等与最终 commit） */
    void updateInternal(RemoteUpdate tx, Change... changes) {
        StatsUpdate statUpdates;
        try (var ignored = Locks.acquire(wLock)) {
            StatsSnapshot old = snapshot;
            snapshot = applyChanges(old, changes);
            statUpdates = diffStats(old, snapshot);
            if (old.hp() != snapshot.hp()) {
                hpChangeAction(old.hp());
            }
        }
        if (!statUpdates.isEmpty()) {
            tx.updateStats(statUpdates);
        }
    }

    /**
     * 锁内纯函数：从旧快照按变更意图产出新快照。
     * 惰性 copy-on-write——只新建实际变化的数组，未变的按引用复用；
     * attrs 任何槽变化或显式 Recalc 都会触发 localAttrs 重算（hyperbody/maple warrior 等耦合使增量不可行）。
     */
    private StatsSnapshot applyChanges(StatsSnapshot old, Change[] changes) {
        // 直接 clone（8 元素成本可忽略）：消除惰性 clone 标志样板，Set/Add/Multiply 统一经 setBase 写入
        int[] clonedBase = old.base().clone();
        boolean baseDirty = false;
        int hp = old.hp();
        int mp = old.mp();
        int ap = old.ap();

        for (Change c : changes) {
            switch (c) {
                case Change.Set(Change.Prop p, int value) -> {
                    if (p.isAttrSlot()) {
                        baseDirty |= setBase(clonedBase, p, value);
                    } else if (p == Change.Prop.HP) {
                        hp = value;
                    } else if (p == Change.Prop.MP) {
                        mp = value;
                    } else if (p == Change.Prop.AP) {
                        ap = value;
                    }
                }
                case Change.Add(Change.Prop p, int delta) -> {
                    if (p.isAttrSlot()) {
                        baseDirty |= setBase(clonedBase, p, clonedBase[p.slot.ordinal()] + delta);
                    } else if (p == Change.Prop.HP) {
                        hp += delta;
                    } else if (p == Change.Prop.MP) {
                        mp += delta;
                    } else if (p == Change.Prop.AP) {
                        ap += delta;
                    }
                }
                case Change.Multiply(Change.Prop p, double multiplier) -> {
                    // NEW = OLD * multiplier（int 截断）；Multiply 仅支持面板属性（HP/MP/AP 无槽位不可乘）
                    assert p.isAttrSlot() : "Multiply 仅支持面板属性槽: " + p;
                    baseDirty |= setBase(clonedBase, p, (int) (clonedBase[p.slot.ordinal()] * multiplier));
                }
            }
        }

        // 事务收尾：面板属性变化 时重算 total；
        // hp/mp 在所有变更算完后统一截断到（recalc 后的）total 上限——同 commit 内 maxHp 提高时 hp 随之放行。
        int[] newTotal = baseDirty ? computeTotal(clonedBase) : this.snapshot.total();
        hp = Math.clamp(hp, 0, newTotal[Stat.MAX_HP.ordinal()]);
        mp = Math.clamp(mp, 0, newTotal[Stat.MAX_MP.ordinal()]);
        ap = Math.max(0, ap);
        return new StatsSnapshot(clonedBase, newTotal, hp, mp, ap);
    }

    /** 写 base 槽（含四维下限兜底与同值跳过），返回是否实际变化 */
    private static boolean setBase(int[] clonedBase, Change.Prop p, int newValue) {
        int idx = p.slot.ordinal();
        if (Stat.SDIL_INDEX_BEGIN <= idx && idx < Stat.SDIL_INDEX_END) {
            if (newValue < 4) {   // 四维下限兜底（原 applyUpdateSilently 语义）
                return false;
            }
        }
        if (clonedBase[idx] == newValue) {
            return false;   // 同值跳过，避免无谓 recalc
        }
        clonedBase[idx] = newValue;
        return true;
    }

    /** 对比新旧快照产出客户端变更集（localAttrs 变化不经 packet，通过 maxHp/hp 等派生体现） */
    private StatsUpdate diffStats(StatsSnapshot old, StatsSnapshot next) {
        StatsUpdate updates = new StatsUpdate();
        for (int i = Stat.SDIL_INDEX_BEGIN; i < Stat.SDIL_INDEX_END; i++) {
            if (old.base()[i] != next.base()[i]) {
                updates.set(Stat.values()[i], next.base()[i]);
            }
        }
        if (old.base()[Stat.MAX_HP.ordinal()] != next.base()[Stat.MAX_HP.ordinal()]) {
            updates.set(Stat.MAX_HP, getClientMaxHp());
            updates.hp(next.hp());
        }
        if (old.base()[Stat.MAX_MP.ordinal()] != next.base()[Stat.MAX_MP.ordinal()]) {
            updates.set(Stat.MAX_MP, getClientMaxMp());
            updates.mp(next.mp());
        }
        if (old.hp() != next.hp()) {
            updates.hp(next.hp());
        }
        if (old.mp() != next.mp()) {
            updates.mp(next.mp());
        }
        if (old.ap() != next.ap()) {
            updates.ap(next.ap());
        }
        return updates;
    }

    /** HP 变化联动：死亡判定 + 队伍 HP 同步 + 狂暴检查 */
    private void hpChangeAction(int oldHp) {
        boolean playerDied = false;
        if (snapshot.hp() <= 0) {
            if (oldHp > snapshot.hp()) {
                playerDied = true;
            }
        }

        final boolean chrDied = playerDied;
        Runnable r = () -> {
            owner.updatePartyMemberHP();    // thanks BHB (BHB88) for detecting a deadlock case within player stats.

            if (chrDied) {
                owner.death.playerDead();
            } else {
                owner.checkBerserk(owner.isHidden());
            }
        };
        if (owner.getMap() != null) {
            owner.getMap().registerCharacterStatUpdate(r);
        }
    }

    /**
     * 装备属性聚合已移至装备域子模块 CharacterEquips.aggregateStatTotals（共享段循环聚合）。
     * todo: [refactor] 结果缓存（equipChanged 标志驱动，在 CharacterEquips 内做），当前全量重算，性能损失可接受。
     */
    private int[] getEquipStats() {
        return owner.inventory.getEquips().aggregateStatTotals();
    }

    /** 纯函数重算 total stats：以 base stats 为初始值，叠加装备/buff/技能加成（recalc 主体） */
    private int[] computeTotal(int[] base) {
        int[] total = base.clone();

        int[] equip = getEquipStats();
        for (int i = 0; i < Stat.count(); i++) {
            total[i] += equip[i];
        }

        total[Stat.M_ATK.ordinal()] = Math.min(total[Stat.M_ATK.ordinal()], 2000);

        Integer hbhp = owner.getBuffedValue(EffectType.HYPERBODYHP);
        if (hbhp != null) {
            total[Stat.MAX_HP.ordinal()] += (int) ((hbhp.doubleValue() / 100) * total[Stat.MAX_HP.ordinal()]);
        }
        Integer hbmp = owner.getBuffedValue(EffectType.HYPERBODYMP);
        if (hbmp != null) {
            total[Stat.MAX_MP.ordinal()] += (int) ((hbmp.doubleValue() / 100) * total[Stat.MAX_MP.ordinal()]);
        }

        total[Stat.MAX_HP.ordinal()] = Math.min(30000, total[Stat.MAX_HP.ordinal()]);
        total[Stat.MAX_MP.ordinal()] = Math.min(30000, total[Stat.MAX_MP.ordinal()]);

        BuffEffectData combo = owner.getBuffEffect(EffectType.ARAN_COMBO);
        if (combo != null) {
            total[Stat.P_ATK.ordinal()] += combo.getX();
        }

        if (owner.getEnergyBar() == 15000) {
            Skill energycharge = owner.isCygnus() ? SkillFactory.getSkill(ThunderBreaker.ENERGY_CHARGE) : SkillFactory.getSkill(Marauder.ENERGY_CHARGE);
            BuffEffectData ceffect = energycharge.getEffect(owner.getSkillLevel(energycharge.getId()));
            total[Stat.P_ATK.ordinal()] += ceffect.getWatk();
        }

        Integer mwarr = owner.getBuffedValue(EffectType.MAPLE_WARRIOR);
        if (mwarr != null) {
            total[Stat.STR.ordinal()] += owner.getStr() * mwarr / 100;
            total[Stat.DEX.ordinal()] += owner.getDex() * mwarr / 100;
            total[Stat.INT.ordinal()] += owner.getInt() * mwarr / 100;
            total[Stat.LUK.ordinal()] += owner.getLuk() * mwarr / 100;
        }
        if (owner.getJob().isA(JobEnum.BOWMAN)) {
            Skill expert = null;
            if (owner.getJob().isA(JobEnum.MARKSMAN)) {
                expert = SkillFactory.getSkill(3220004);
            } else if (owner.getJob().isA(JobEnum.BOWMASTER)) {
                expert = SkillFactory.getSkill(3120005);
            }
            if (expert != null) {
                int boostLevel = owner.getSkillLevel(expert.getId());
                if (boostLevel > 0) {
                    total[Stat.P_ATK.ordinal()] += expert.getEffect(boostLevel).getX();
                }
            }
        }

        Integer watkbuff = owner.getBuffedValue(EffectType.WATK);
        if (watkbuff != null) {
            total[Stat.P_ATK.ordinal()] += watkbuff;
        }
        Integer matkbuff = owner.getBuffedValue(EffectType.MATK);
        if (matkbuff != null) {
            total[Stat.M_ATK.ordinal()] += matkbuff;
        }

        int blessing = owner.getSkillLevel(10000000 * owner.getJobType() + 12);
        if (blessing > 0) {
            total[Stat.P_ATK.ordinal()] += blessing;
            total[Stat.M_ATK.ordinal()] += blessing * 2;
        }

        if (owner.getJob().isA(JobEnum.THIEF) || owner.getJob().isA(JobEnum.BOWMAN) || owner.getJob().isA(JobEnum.PIRATE) || owner.getJob().isA(JobEnum.NIGHTWALKER1) || owner.getJob().isA(JobEnum.WINDARCHER1)) {
            ItemSlot weapon_item = owner.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
            if (weapon_item != null) {
                WeaponTypeDefinition weapon = WeaponTypeRegistry.of(weapon_item.getItemId());
                if (weapon.ammoIdRange() != null) {
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    InventoryTab inv = owner.getInventory(InventoryType.USE);
                    for (int i = 1; i <= inv.getSlotLimit(); i++) {
                        ItemSlot item = inv.getItem(i);
                        if (item == null) {
                            continue;
                        }
                        if (weapon.usesAmmo(item.getItemId())) {
                            if (item.getQuantity() > 0) {
                                total[Stat.P_ATK.ordinal()] += ii.getWatkForProjectile(item.getItemId());
                                break;
                            }
                        }
                    }
                }
            }
        }

        owner.chair.invalidateHealStats();    // 装备/属性变化后椅子恢复参数需重算
        return total;
    }

    // ── 持久化数据转换（stats 域的映射；信封 CharacterData 的组装/应用在 Character.toData/applyData） ──

    CharacterStatsData toData() {
        StatsSnapshot s = snapshot;
        CharacterStatsData d = new CharacterStatsData();
        d.str = s.base()[Stat.STR.ordinal()];
        d.dex = s.base()[Stat.DEX.ordinal()];
        d.int_ = s.base()[Stat.INT.ordinal()];
        d.luk = s.base()[Stat.LUK.ordinal()];
        d.hp = s.hp();
        d.mp = s.mp();
        d.maxHp = s.base()[Stat.MAX_HP.ordinal()];
        d.maxMp = s.base()[Stat.MAX_MP.ordinal()];
        return d;
    }

    void applyData(CharacterStatsData d) {
        try (var ignored = Locks.acquire(wLock)) {
            int[] attrs = new int[Stat.count()];
            attrs[Stat.STR.ordinal()] = d.str;
            attrs[Stat.DEX.ordinal()] = d.dex;
            attrs[Stat.INT.ordinal()] = d.int_;
            attrs[Stat.LUK.ordinal()] = d.luk;
            attrs[Stat.MAX_HP.ordinal()] = d.maxHp;
            attrs[Stat.MAX_MP.ordinal()] = d.maxMp;
            // P_ATK/M_ATK 裸体 0（仅装备/buff 累加，localAttrs 从 attrs 起步）
            int[] local = new int[Stat.count()];
            local[Stat.MAX_HP.ordinal()] = 50;   // recalc 前的初始上限
            local[Stat.MAX_MP.ordinal()] = 5;
            snapshot = new StatsSnapshot(attrs, local, d.hp, d.mp, snapshot.ap());
        }
    }

    // ══════════════════ legacy APIs (to be refactored) ══════════════════

    /** 客户端可见最大 HP（封顶 30000，客户端上限） */
    int getClientMaxHp() {  // fixme: [refactor] move to packet
        return Math.min(30000, snapshot.base()[Stat.MAX_HP.ordinal()]);
    }

    /** 客户端可见最大 MP（封顶 30000，客户端上限） */
    int getClientMaxMp() {  // fixme: [refactor] move to packet
        return Math.min(30000, snapshot.base()[Stat.MAX_MP.ordinal()]);
    }

    /** 魔法攻击强度 = 魔法攻击力 + 智力（魔法侧两步合并成一步数值等价；与物理的"攻击力存储 + 强度现算"对称） */
    int getMagicPower() {  // fixme: [refactor] move outside
        return snapshot.total()[Stat.M_ATK.ordinal()] + snapshot.total()[Stat.INT.ordinal()];
    }

    int getRemainingAp() {  // fixme: [refactor] move outside
        return snapshot.ap();
    }

    /** HP/MP 变更（含自动药水触发与 GM 保护），返回是否成功应用 */
    boolean applyHpMpChange(int hpCon, int hpchange, int mpchange) {
        boolean zombify = owner.hasDisease(Disease.ZOMBIFY);

        try (var ignored = Locks.acquire(wLock)) {
            int nextHp = snapshot.hp() + hpchange, nextMp = snapshot.mp() + mpchange;
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

            update().setHp(nextHp).setMp(nextMp).commit();
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
                        ItemSlot autoHpItem = owner.getInventory(InventoryType.USE).findById(autoHpItemId);
                        if (autoHpItem != null) {
                            PetAutopotProcessor.runAutopotAction(owner.client, (short) autoHpItem.getPosition(), autoHpItemId);
                        }
                    }
                }
            }

            if (mpchange < 0) {
                KeyBinding autoMpPot = owner.getKeymap().get(92);
                if (autoMpPot != null) {
                    int autoMpItemId = autoMpPot.getAction();
                    if (((float) owner.getMp()) / owner.getCurrentMaxMp() <= autoMpAlert) {
                        ItemSlot autoMpItem = owner.getInventory(InventoryType.USE).findById(autoMpItemId);
                        if (autoMpItem != null) {
                            PetAutopotProcessor.runAutopotAction(owner.client, (short) autoMpItem.getPosition(), autoMpItemId);
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
