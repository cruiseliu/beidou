package org.gms.client.inventory;

import java.util.EnumSet;
import org.gms.client.JobEnum;
import org.gms.client.character.Character;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.skills.Assassin;
import org.gms.constants.skills.Gunslinger;
import org.gms.constants.skills.NightWalker;
import org.gms.model.json.ItemData;
import org.gms.server.ItemInformationProvider;

/**
 * 物品本体：与槽位无关的物品数据（id/宠物/署名/旗标/到期 + 现金域/装备域组件）。
 * 槽位概念（position/quantity）在 {@link ItemSlot}——本类经其构造（slot 引用仅用于
 * 装备域组件的宿主接线）；ItemSlot 保留全部旧方法作为重构期兼容门面。
 */
public class Item implements Comparable<Item> {
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    int id;
    EnumSet<ItemFlag> flagSet = EnumSet.noneOf(ItemFlag.class);
    String owner = "";
    long expiration = -1;

    /** 装备域信息（属性数组/成长等级等）；构造时按背包类型定性——非装备物品恒为 null */
    Equip equipInfo;

    /** 点券物品会话信息（cashId/sn/giftFrom）；构造时按 isCash 定性——非现金物品恒为 null */
    CashItemInfo cashInfo;

    int petId = -1;

    /** 可充值物品（飞镖/子弹）的可使用次数；新表示下 quantity 恒 1（一组一格），次数存此。
     *  非可充值物品不使用本字段。 */
    int charge;

    Item(int id) {
        this.id = id;
        equipInfo = getInventoryTab() == InventoryType.EQUIP ? new Equip(id) : null;
        cashInfo = ii.isCash(id) ? new CashItemInfo() : null;
        applyWzTypeFlags();
    }

    Item(int id, int petid) {
        this.id = id;
        this.equipInfo = getInventoryTab() == InventoryType.EQUIP ? new Equip(id) : null;
        this.cashInfo = ii.isCash(id) ? new CashItemInfo() : null;
        this.petId = petid;
        applyWzTypeFlags();
    }

    private Item() {
    }

    public Item copy() {
        Item ret = new Item();
        ret.id = id;
        ret.flagSet.addAll(flagSet);
        ret.owner = owner;
        ret.expiration = expiration;
        if (equipInfo != null) {
            ret.equipInfo = equipInfo.copy();
        }
        ret.cashInfo = ii.isCash(id) ? new CashItemInfo() : null;
        ret.petId = petId;
        ret.charge = charge;
        return ret;
    }

    /** 反序列化工厂：宿主先建（装备组件暂空），装备域经 {@link Equip#fromData} 补建后回填；
     *  对象发布前即处于完整正确状态 */
    public static Item fromData(ItemData d) {
        Item it = new Item();
        it.id = d.itemId;
        it.cashInfo = ii.isCash(d.itemId) ? new CashItemInfo() : null;
        it.petId = d.petId == null ? -1 : d.petId;
        if (d.equip != null) {
            it.equipInfo = Equip.fromData(it, d.equip);
        }
        if (d.itemFlags != null) {
            for (String name : d.itemFlags) {
                it.addFlag(ItemFlag.valueOf(name));
            }
        }
        if (d.charge != null && d.charge > 0) {
            it.charge = d.charge;   // 可充值次数直接注入（quantity 组数语义不受影响）
        }
        if (d.owner != null) {
            it.owner = d.owner;
        }
        if (d.expiration != null) {
            it.expiration = d.expiration;
        }
        return it;
    }

    /** 构造期 wz 类型级 flag 物化（2026-08-27 定调：实例 flag = 唯一真相）。
     *  tradeBlock 不只存在于装备——活动发放的绑定药水等普通物品同样携带，出生即落位；
     *  accountSharable 对齐 setFlag 的既有语义。SPIKES(wz info/fs) 仍归 Equip 域（initWzBaseStats）。
     *
     *  TODO [refactor] 强制侧谓词收拢：丢弃/交易/isUntradeable 三处组合式各不相同，
     *  应统一为“实例 flag 为准 + karma 豁免一处定义”，查询制降级为兜底；
     *  另注意并堆条件 getFlag()!=0——落位物品不再叠堆，官方行为对齐待验证。 */
    private void applyWzTypeFlags() {
        if (ii.isUntradeableRestricted(id)) {
            addFlag(ItemFlag.UNTRADEABLE);
        }
        if (ii.isAccountRestricted(id)) {
            addFlag(ItemFlag.ACCOUNT_SHARING);
        }
        if (ii.isKarmaAble(id)) {
            addFlag(ItemFlag.SCISSOR_USABLE);   // 宿命剪刀可用性恒定标签
        }
    }

    public int getItemId() {
        return id;
    }

    /** 是否可充值物品（飞镖/子弹）——quantity 语义为"组数"（恒 1），次数在 charge */
    public boolean isRechargeable() {
        return isRechargeable(id);
    }

    public static boolean isRechargeable(int itemId) {
        return ItemConstants.isRechargeable(itemId);
    }

    /** 工厂：创建携带可使用次数的可充值物品本体（"一组一格"表示中的该组；供包外构造） */
    public static Item rechargeable(int itemId, int charge) {
        Item item = new Item(itemId);
        item.charge = charge;
        return item;
    }

    public int getCharge() {
        return charge;
    }

    public void setCharge(int charge) {
        this.charge = charge;
    }

    /** 单组可携带次数上限（委托静态版）；仅对可充值物品有意义 */
    public int getChargeLimit(Character chr) {
        return getChargeLimit(id, chr);
    }

    /**
     * 单组可携带次数上限：wz slotMax 基数 + 精通技能加成（每级 +10；飞镖=Claw Mastery
     * ——夜行者走自己职业链，其余刺客系走 Assassin；子弹=Gun Mastery）。仅对可充值物品有意义。
     * 原 ItemInformationProvider.getExtraSlotMaxFromPlayer 的迁入版。
     */
    public static int getChargeLimit(int itemId, Character chr) {
        short base = ii.getSlotMax(itemId);
        if (ItemConstants.isThrowingStar(itemId)) {
            int masteryId = chr.getJob().isA(JobEnum.NIGHTWALKER1) ? NightWalker.CLAW_MASTERY : Assassin.CLAW_MASTERY;
            return base + chr.getSkillLevel(masteryId) * 10;
        }
        if (ItemConstants.isBullet(itemId)) {
            return base + chr.getSkillLevel(Gunslinger.GUN_MASTERY) * 10;
        }
        return base;
    }

    public InventoryType getInventoryTab() {
        return ItemConstants.getInventoryType(id);
    }

    public int getItemType() { // 1: equip, 3: pet, 2: other
        // todo: [refactor] smell
        if (equipInfo != null) {
            return 1;
        }
        if (petId > -1) {
            return 3;
        }
        return 2;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public int getPetId() {
        return petId;
    }

    // ── 实例旗标（类型化；legacy 位值只在存档/协议组装点出现）──

    public boolean hasFlag(ItemFlag f) {
        return flagSet.contains(f);
    }

    public void addFlag(ItemFlag f) {
        flagSet.add(f);
    }

    public void removeFlag(ItemFlag f) {
        flagSet.remove(f);
    }

    public EnumSet<ItemFlag> flags() {
        return flagSet.clone();
    }

    /**
     * 并堆判定：
     * <ol>
     *   <li>stack limit &gt; 1（天然排除装备与可充值——故旗标比较只需看 Item 域集合，
     *       不涉及 EquipFlag）</li>
     *   <li>itemId 相等</li>
     *   <li>旗标集相等</li>
     *   <li>署名（owner string）相等</li>
     * </ol>
     */
    public boolean canMergeWith(Item other) {
        return getStackLimit() > 1
                && id == other.id
                && flagSet.equals(other.flagSet)
                && owner.equals(other.owner);
    }

    /** 从旧整型旗标恢复：按类别分桶到 Item/Equip 两组（0x02 在装备上是 SPIKES） */
    public void setFlagsFromLegacy(int raw) {
        flagSet.clear();
        ItemFlag.collectFromLegacy(raw, getInventoryTab() == InventoryType.EQUIP, flagSet);
        if (equipInfo != null) {
            equipInfo.LEGACY_setFlagsFromLegacy(raw);
        }
    }

    /** 过渡方法：旧版整型 flag 的组装视图——仅供客户端协议编码与存档列读写，
     *  服务端判定禁止再读它。 */
    public int getLegacyFlags() {
        // 组装唯一实现见 remote 层工具方法；此处仅为存档列兼容的过渡出口
        return org.gms.remote.v83.V83RemoteClient.assembleClientFlagBits(this);
    }

    public long getExpiration() {
        return expiration;
    }

    public void setExpiration(long expire) {
        this.expiration = !ItemConstants.isPermanentItem(id) ? expire : ItemConstants.isPet(id) ? Long.MAX_VALUE : -1;
    }

    /** 点券物品会话信息；非现金物品为 null */
    public CashItemInfo getCashInfo() {
        return cashInfo;
    }

    /** 是否现金物品（构造时按 isCash 定性；cashId/sn/giftFrom 仅现金物品携带） */
    public boolean isCashItem() {
        return cashInfo != null;
    }

    /** 装备域信息；非装备物品为 null */
    public Equip getEquipInfo() {
        return equipInfo;
    }

    @Override
    public int compareTo(Item other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public String toString() {
        return "Item: " + id;
    }

    public int getStackLimit() {
        return getStackLimit(id);
    }

    public static int getStackLimit(int itemId) {
        return isRechargeable(itemId) ? 1 : ii.getSlotMax(itemId);
    }
}
