package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.Disease;
import org.gms.client.keybind.KeyBinding;
import org.gms.client.processor.action.PetAutopotProcessor;
import org.gms.client.JobEnum;
import org.gms.client.Skill;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.weaponType.WeaponTypeDefinition;
import org.gms.client.weaponType.WeaponTypeRegistry;
import org.gms.server.maps.MapleMapRef;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.skills.Marauder;
import org.gms.constants.skills.ThunderBreaker;
import org.gms.model.json.CharacterStatsData;

import org.gms.remote.modules.stats.server.StatsUpdate;
import org.gms.server.BuffEffectData;
import org.gms.server.ItemInformationProvider;
import org.gms.util.PacketCreator;

/**
 * 角色属性数据：直写模型（player strand 单线程纪律下无锁直读写）。
 * <p>
 * 状态为裸字段（base/total 数组 + hp/mp/ap 标量）；写路径 = 单属性直写原语（本类公开面），
 * setter 自产自己的 diff（写哪个槽、带什么派生字段由各 setter 明示），无变化即无事件。
 * 不提供跨字段组合 setter——跨字段的包合并由调用方 RemoteClient.batch 表达。
 * 无锁、无快照——锁时代的 volatile snapshot + RW lock + StatUpdateBuilder/Change 已随 actor 模型迁移退役。
 * <p>
 * 已知 off-strand 残留触点（椅子恢复定时器/跨角色 HP 读/保存采集）无同步保护，
 * 竞态留 FIXME 由相应模块重构时收敛（caller 纪律问题，测试场景不覆盖）。
 */
public class CharacterStats {
    private final Character owner;

    // 直写状态（裸字段；数组下标 = Stat.ordinal()）。total 由 recalc/事务收尾整体换新数组，
    // base 只就地变异、引用永不换。跨线程访问无同步——见类注 FIXME 纪律。
    private final int[] base = new int[Stat.count()];
    private int[] total = new int[Stat.count()];
    private int hp;
    private int mp;
    private int ap;

    CharacterStats(Character owner) {
        this.owner = owner;
        total[Stat.MAX_HP.ordinal()] = 50;   // recalc 前的初始上限（与历史字段默认一致）
        total[Stat.MAX_MP.ordinal()] = 5;
    }

    // ── 读路径（strand 内直读） ──

    int getBase(Stat stat) {
        return base[stat.ordinal()];
    }

    int getTotal(Stat stat) {
        return total[stat.ordinal()];
    }

    int getHp() {
        return hp;
    }

    int getMp() {
        return mp;
    }

    /**
     * base 数组只读视图（验证类调用方立即拷贝，不得持有/修改）。
     * 返回活引用是直写模型的固有约定：调用方与写路径同在 strand 上时无撕裂窗口。
     */
    int[] base() {
        return base;
    }

    // ── 写路径（无锁直写 API，全部单属性原语）──
    //
    // setter 自产自己的 diff（它清楚自己改了哪个槽），不做新旧状态对比；无变化即无事件。
    // 跨字段包合并由调用方 RemoteClient.batch 表达（一次收口一个净 diff 包）；
    // 静默只存在于装配/加载入口（applyData / setAp(true)）。原 StatUpdateBuilder/Change 已退役。

    // ══ HP/MP/AP（资源语义：无 total 联动）══

    /** 设置 HP（公告；clamp 到当前 total 上限） */
    void setHp(int value) {
        int oldHp = hp;
        hp = Math.clamp(value, 0, total[Stat.MAX_HP.ordinal()]);
        if (hp == oldHp) {
            return;
        }
        hpChangeAction(oldHp);   // 死亡判定/队伍同步/狂暴（跨 actor 延迟，与包序无关）
        owner.remote().stats().updateStats(new StatsUpdate().hp(hp));
    }

    /** 设置 MP（公告；clamp 到当前 total 上限） */
    void setMp(int value) {
        int oldMp = mp;
        mp = Math.clamp(value, 0, total[Stat.MAX_MP.ordinal()]);
        if (mp == oldMp) {
            return;
        }
        owner.remote().stats().updateStats(new StatsUpdate().mp(mp));
    }

    /** 增加 HP（公告） */
    void addHp(int delta) {
        setHp(hp + delta);
    }

    /** 增加 MP（公告） */
    void addMp(int delta) {
        setMp(mp + delta);
    }

    /**
     * 设置剩余 AP。全域唯一带 silent 的写入口：装配/加载路径传 true（不经公告管道），运行期传 false。
     */
    void setAp(int value, boolean silent) {
        int oldAp = ap;
        ap = Math.max(0, value);
        if (silent || ap == oldAp) {
            return;
        }
        owner.remote().stats().updateStats(new StatsUpdate().ap(ap));
    }

    /** 增加剩余 AP（公告，下限 0） */
    void addAp(int delta) {
        int oldAp = ap;
        ap = Math.max(0, ap + delta);
        if (ap == oldAp) {
            return;
        }
        owner.remote().stats().updateStats(new StatsUpdate().ap(ap));
    }

    // ══ base 槽（面板属性：写后重算 total；MAX_HP/MAX_MP 变化附带封顶显示值 + 当前资源）══

    /**
     * 设置单个 base 槽（公告）：SDIL 槽带下限兜底与同值跳过。
     * MAX_HP/MAX_MP 变化附带封顶显示值 + 当前资源（客户端 max 变更时重置资源显示）；
     * 装备段槽位（P_DEF..HANDS）不产 STAT_CHANGED，仅 total 随写重算（与旧 diff 扫描范围一致）。
     */
    void setBaseStat(Stat s, int value) {
        if (!writeBase(s, value)) {
            return;   // 四维下限兜底或同值：无变化无事件
        }
        int oldHp = hp;
        total = computeTotal(base);
        clampResources();

        StatsUpdate updates = new StatsUpdate();
        if (s == Stat.MAX_HP) {
            updates.set(Stat.MAX_HP, getClientMaxHp());
            updates.hp(hp);
        } else if (s == Stat.MAX_MP) {
            updates.set(Stat.MAX_MP, getClientMaxMp());
            updates.mp(mp);
        } else if (isSdil(s)) {
            updates.set(s, base[s.ordinal()]);
        } else {
            return;
        }

        if (oldHp != hp) {
            hpChangeAction(oldHp);   // max 降导致的 clamp 可能减 hp
        }
        owner.remote().stats().updateStats(updates);
    }

    /** 增加单个 base 槽（公告） */
    void addBaseStat(Stat s, int delta) {
        setBaseStat(s, base[s.ordinal()] + delta);
    }

    /**
     * 单槽成长写：NEW = (OLD + gain) * mult（int 截断；公告）——升级/转职"有加有乘"公式。
     */
    void growBaseStat(Stat s, int gain, double mult) {
        setBaseStat(s, (int) ((base[s.ordinal()] + gain) * mult));
    }

    /**
     * 重算 total stats（API 层面独立调用，非属性写入）：装备/buff 变化后触发，
     * 就地换新 total 并按新上限 clamp hp/mp（静默，不公告——面板 total 由客户端自算）。
     */
    void recalc() {
        int[] newTotal = computeTotal(base);
        hp = Math.clamp(hp, 0, newTotal[Stat.MAX_HP.ordinal()]);
        mp = Math.clamp(mp, 0, newTotal[Stat.MAX_MP.ordinal()]);
        total = newTotal;
    }

    /** 重算 total stats 并在 maxHp 变化时同步队伍成员 HP（buff 变化/换装后的联动） */
    void recalcAndSyncParty() {
        int oldmaxhp = total[Stat.MAX_HP.ordinal()];
        recalc();
        if (oldmaxhp != total[Stat.MAX_HP.ordinal()]) {
            owner.updatePartyMemberHP();
        }
    }

    /** 受击扣血钳制（HP 不低于 1）：先按旧 hp 定实际 delta 再走公告事务 */
    int safeAddHP(int delta) {
        if (hp + delta <= 0) {
            delta = 1 - hp;
        }
        addHp(delta);
        return delta;
    }
    // ── private methods（直写内核）──

    /** 资源收尾钳制：hp/mp clamp 到当前 total 上限，ap 下限 0（base 写后收尾用） */
    private void clampResources() {
        hp = Math.clamp(hp, 0, total[Stat.MAX_HP.ordinal()]);
        mp = Math.clamp(mp, 0, total[Stat.MAX_MP.ordinal()]);
        ap = Math.max(0, ap);
    }

    private static boolean isSdil(Stat s) {
        return Stat.SDIL_INDEX_BEGIN <= s.ordinal() && s.ordinal() < Stat.SDIL_INDEX_END;
    }

    /** 写 base 槽（含四维下限兜底与同值跳过），返回是否实际变化 */
    private boolean writeBase(Stat s, int newValue) {
        int idx = s.ordinal();
        if (isSdil(s)) {
            if (newValue < 4) {   // 四维下限兜底（原 applyUpdateSilently 语义）
                return false;
            }
        }
        if (base[idx] == newValue) {
            return false;   // 同值跳过，避免无谓 recalc
        }
        base[idx] = newValue;
        return true;
    }

    /** HP 变化联动：死亡判定 + 队伍 HP 同步 + 狂暴检查 */
    private void hpChangeAction(int oldHp) {
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
                owner.death.playerDead();
            } else {
                owner.checkBerserk(owner.isHidden());
            }
        };
        MapleMapRef mapRef = owner.getMapRef();
        if (mapRef != null) {
            mapRef.registerCharacterStatUpdate(r);
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
        CharacterStatsData d = new CharacterStatsData();
        d.str = base[Stat.STR.ordinal()];
        d.dex = base[Stat.DEX.ordinal()];
        d.int_ = base[Stat.INT.ordinal()];
        d.luk = base[Stat.LUK.ordinal()];
        d.hp = hp;
        d.mp = mp;
        d.maxHp = base[Stat.MAX_HP.ordinal()];
        d.maxMp = base[Stat.MAX_MP.ordinal()];
        return d;
    }

    void applyData(CharacterStatsData d) {
        base[Stat.STR.ordinal()] = d.str;
        base[Stat.DEX.ordinal()] = d.dex;
        base[Stat.INT.ordinal()] = d.int_;
        base[Stat.LUK.ordinal()] = d.luk;
        base[Stat.MAX_HP.ordinal()] = d.maxHp;
        base[Stat.MAX_MP.ordinal()] = d.maxMp;
        // P_ATK/M_ATK 裸体 0（仅装备/buff 累加，total 从 base 起步）
        total = new int[Stat.count()];
        total[Stat.MAX_HP.ordinal()] = 50;   // recalc 前的初始上限
        total[Stat.MAX_MP.ordinal()] = 5;
        hp = d.hp;
        mp = d.mp;
        // ap 不属 stats 域加载，保持现值（CharacterAp.applyData 负责）
    }

    // ══════════════════ legacy APIs (to be refactored) ══════════════════

    /** 客户端可见最大 HP（封顶 30000，客户端上限） */
    int getClientMaxHp() {  // fixme: [refactor] move to packet
        return Math.min(30000, base[Stat.MAX_HP.ordinal()]);
    }

    /** 客户端可见最大 MP（封顶 30000，客户端上限） */
    int getClientMaxMp() {  // fixme: [refactor] move to packet
        return Math.min(30000, base[Stat.MAX_MP.ordinal()]);
    }

    /** 魔法攻击强度 = 魔法攻击力 + 智力（魔法侧两步合并成一步数值等价；与物理的"攻击力存储 + 强度现算"对称） */
    int getMagicPower() {  // fixme: [refactor] move outside
        return total[Stat.M_ATK.ordinal()] + total[Stat.INT.ordinal()];
    }

    int getRemainingAp() {  // fixme: [refactor] move outside
        return ap;
    }

    /** HP/MP 变更（含自动药水触发与 GM 保护），返回是否成功应用 */
    boolean applyHpMpChange(int hpCon, int hpchange, int mpchange) {
        boolean zombify = owner.hasDisease(Disease.ZOMBIFY);

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

        try (var _b = owner.remote().batch()) {   // hp+mp 同批收口（0x1400 单包语义保持）
            setHp(nextHp);
            setMp(nextMp);
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
