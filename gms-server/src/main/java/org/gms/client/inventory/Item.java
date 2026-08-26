package org.gms.client.inventory;

import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;

/**
 * 物品本体：与槽位无关的物品数据（id/宠物/署名/旗标/到期 + 现金域/装备域组件）。
 * 槽位概念（position/quantity）在 {@link ItemSlot}——本类经其构造（slot 引用仅用于
 * 装备域组件的宿主接线）；ItemSlot 保留全部旧方法作为重构期兼容门面。
 */
public class Item implements Comparable<Item> {
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    final int id;

    int flag;
    String owner = "";
    long expiration = -1;

    /** 点券物品会话信息（cashId/sn/giftFrom）；构造时按 isCash 定性——非现金物品恒为 null */
    final CashItemInfo cashInfo;

    /** 装备域信息（属性数组/成长等级等）；构造时按背包类型定性——非装备物品恒为 null */
    Equip equipInfo;

    int petId = -1;

    /** 可充值物品（飞镖/子弹）的可使用次数；新表示下 quantity 恒 1（一组一格），次数存此。
     *  非可充值物品不使用本字段。 */
    int charge;

    Item(int id, int position, int petid) {
        this.id = id;
        this.petId = petid;
        this.flag = 0;
        this.cashInfo = ii.isCash(id) ? new CashItemInfo() : null;
        this.equipInfo = getInventoryType() == InventoryType.EQUIP ? new Equip(this, id) : null;
    }

    public int getItemId() {
        return id;
    }

    /** 是否可充值物品（飞镖/子弹）——quantity 语义为"组数"（恒 1），次数在 charge */
    public boolean isRechargeable() {
        return ItemConstants.isRechargeable(id);
    }

    /** 工厂：创建携带可使用次数的可充值物品本体（"一组一格"表示中的该组；供包外构造） */
    public static Item rechargeable(int itemId, int charge) {
        Item item = new Item(itemId, 0, -1);
        item.charge = charge;
        return item;
    }

    public int getCharge() {
        return charge;
    }

    public void setCharge(int charge) {
        this.charge = charge;
    }

    // ── 持久化数据转换（inventory 域；槽位 position/quantity 经宿主 slot 读写，信封组装在 Character.toData） ──

    // public ItemData toData() {
    //     ItemData d = new ItemData();
    //     d.itemId = id;
    //     d.flag = flag == 0 ? null : flag;
    //     d.owner = owner.isEmpty() ? null : owner;
    //     d.expiration = expiration == -1 ? null : expiration;
    //     d.petId = petId == -1 ? null : petId;
    //     if (equipInfo != null) {
    //         d.equip = equipInfo.toData();
    //     }
    //     return d;
    // }

    // public void applyData(ItemData d) {
    //     if (d.flag != null) flag = d.flag;
    //     if (d.owner != null) owner = d.owner;
    //     if (d.expiration != null) expiration = d.expiration;
    //     if (d.petId != null) petId = d.petId;
    //     if (d.equip != null && equipInfo != null) {
    //         equipInfo.applyData(d.equip);
    //     }
    // }

    public InventoryType getInventoryType() {
        return ItemConstants.getInventoryType(id);
    }

    public int getItemType() { // 1: equip, 3: pet, 2: other
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

    public int getFlag() {
        return flag;
    }

    public void setFlag(int b) {
        if (ii.isAccountRestricted(id)) {
            b |= ItemConstants.ACCOUNT_SHARING; // thanks Shinigami15 for noticing ACCOUNT_SHARING flag not being applied properly to items server-side
        }
        this.flag = b;
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
}
