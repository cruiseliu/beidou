package org.gms.remote.gms083.server.blocks;

import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.client.pet.Pet;
import org.gms.constants.game.ExpTable;
import org.gms.constants.inventory.ItemConstants;
import org.gms.remote.gms083.server.translators.Filetimes;
import org.gms.remote.gms083.utils.ByteBufBuilder;
import org.gms.server.ItemInformationProvider;

/**
 * 道具条目 wire 块（v83）：INVENTORY_OPERATION 条目与 SET_FIELD inventory 段的共用编码单元。
 * 字段 = 基础类型/字符串/容器（构造时固化）；encode 纯字段重放——位置归各包帧结构
 * （IO 的 mode/tab/pos 头、SET_FIELD 的槽位直写、legacy addItemInfo 的内联位置），
 * 本块只编码 itemType 起的条目体：公共头（type/id/cash/serial/expiration）+ 三分支。
 *
 * <p>语义查表全部在工厂完成：cash 判定、serial 三选一（宠物=petId、戒指=ringId、
 * 其余=cashId）、到期映射（{@link Filetimes}）、可充值 wire 数量（= charge 次数）、
 * 成长经验 nibble（ExpTable）、宠物到期三分支与 tameness 面板截断（min 30000）。
 * 字符串（owner/宠物名）经输出 builder 的 charset 编码——消费方以 lang-0 builder
 * 输出即为既定字节。
 */
public sealed interface ItemBlock {

    byte type();

    int itemId();

    boolean cash();

    long serial();

    long expiration();

    /** 条目体编码：公共头 + 分支载荷 */
    default void encode(ByteBufBuilder out) {
        out.writeByte(type());
        out.writeInt(itemId());
        out.writeBool(cash());
        if (cash()) {
            out.writeLong(serial());
        }
        out.writeLong(expiration());
        switch (this) {
            case Equip e -> e.encodeBody(out);
            case Pet p -> p.encodeBody(out);
            case Stack s -> s.encodeBody(out);
        }
    }

    // ── 工厂：实体/快照 → wire 字段（取值与换算在构造时完成，encode 零取值逻辑）──

    /**
     * Item 本体 + 语义数量 → Equip/Stack 块。可充值 wire 数量 = 可使用次数（charge，
     * 本工厂换算）；其余 wire 数量 = 语义数量。宠物禁入——必经冻结快照 {@link #ofPet}。
     */
    static ItemBlock of(Item item, int quantity) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int itemId = item.getItemId();
        boolean cash = ii.isCash(itemId);
        byte type = (byte) item.getItemType();
        if (type == 3) {
            throw new IllegalStateException("宠物物品体缺冻结快照: " + itemId);
        }
        long serial = 0;
        if (cash && item.getCashInfo() != null) {
            serial = item.getCashInfo().getCashId();
        }
        if (type == 1) {
            org.gms.client.inventory.Equip equip = item.getEquipInfo();
            if (cash && equip.getRingId() > -1) {
                serial = equip.getRingId();
            }
            LevelInfo levelInfo = cash
                    ? new LevelInfo.CashPadding()
                    : new LevelInfo.Growth((byte) 0,
                            (byte) equip.getItemLevel(),
                            (int) (ExpTable.getExpNeededForLevel(ii.getEquipLevelReq(itemId)) * equip.getItemExp()
                                    / ExpTable.getEquipExpNeededForLevel(equip.getItemLevel())),
                            equip.getVicious(), 0L);
            return new Equip(itemId, cash, serial, Filetimes.toWire(item.getExpiration()),
                    (byte) equip.getEnhancementSlots(), (byte) equip.getEnhancementLevel(),
                    equipStats(equip), item.getOwner(), item.getLegacyFlags(),
                    levelInfo, Filetimes.toWire(-2), -1);
        }
        short wireQuantity = (short) (item.isRechargeable() ? item.getCharge() : quantity);
        return new Stack(itemId, cash, serial, Filetimes.toWire(item.getExpiration()),
                wireQuantity, item.getOwner(), item.getLegacyFlags(),
                ItemConstants.isRechargeable(itemId));
    }

    /**
     * 宠物块（PetModule 面板快照与 inventory 槽位共用）。宠物到期归 Pet
     * （item.expiration 恒 -1）；客户端语义（实测）：wire ≥ EXPIRED 显示"过期"，
     * PERMANENT 显示"永久"，其余显示日期。tameness 面板截断：服务端可持有超出值，
     * 客户端只显示 30000。
     */
    static ItemBlock ofPet(int itemId, long petId, String name, int level, int tameness,
                           int fullness, int flags, boolean alive, long expiration) {
        long wireExpiration;
        if (!alive) {
            wireExpiration = Filetimes.EXPIRED;              // 失活 → "过期"
        } else if (expiration == -1) {
            wireExpiration = Filetimes.PERMANENT;            // 永久 → "永久"
        } else {
            wireExpiration = Filetimes.toWire(expiration);
        }
        return new Pet(itemId, true, petId, wireExpiration, name,
                (byte) level, (short) Math.min(tameness, 30000), (byte) fullness, (short) flags);
    }

    /** 宠物槽位（Item 本体 + 宿主 Pet 实体）→ 宠物块（SET_FIELD inventory 段用） */
    static ItemBlock ofPet(Item item, org.gms.client.pet.Pet pet) {
        return ofPet(item.getItemId(), item.getPetId(), pet.getName(), pet.getLevel(),
                pet.getTameness(), pet.getFullness(), pet.getFlags(), pet.isAlive(),
                pet.getExpiration());
    }

    private static short[] equipStats(org.gms.client.inventory.Equip equip) {
        return new short[]{
                (short) equip.getStat(org.gms.client.character.Stat.STR),
                (short) equip.getStat(org.gms.client.character.Stat.DEX),
                (short) equip.getStat(org.gms.client.character.Stat.INT),
                (short) equip.getStat(org.gms.client.character.Stat.LUK),
                (short) equip.getStat(org.gms.client.character.Stat.MAX_HP),
                (short) equip.getStat(org.gms.client.character.Stat.MAX_MP),
                (short) equip.getStat(org.gms.client.character.Stat.P_ATK),
                (short) equip.getStat(org.gms.client.character.Stat.M_ATK),
                (short) equip.getStat(org.gms.client.character.Stat.P_DEF),
                (short) equip.getStat(org.gms.client.character.Stat.M_DEF),
                (short) equip.getStat(org.gms.client.character.Stat.ACCURACY),
                (short) equip.getStat(org.gms.client.character.Stat.AVOIDABILITY),
                (short) equip.getStat(org.gms.client.character.Stat.HANDS),
                (short) equip.getStat(org.gms.client.character.Stat.SPEED),
                (short) equip.getStat(org.gms.client.character.Stat.JUMP),
        };
    }

    /** 装备块：15 项属性 + owner/flags + 成长段（现金=0x40 填充）+ 尾部（ZERO_TIME + -1） */
    record Equip(int itemId, boolean cash, long serial, long expiration,
                 byte tuc, byte level, short[] stats, String owner, int flags,
                 LevelInfo levelInfo, long craftTime, int tail) implements ItemBlock {
        private static final int STAT_COUNT = 15;

        public Equip {
            if (stats.length != STAT_COUNT) {
                throw new IllegalArgumentException("装备属性段必须为 15 项");
            }
        }

        @Override
        public byte type() {
            return 1;
        }

        private void encodeBody(ByteBufBuilder out) {
            out.writeByte(tuc);
            out.writeByte(level);
            for (short statValue : stats) {
                out.writeShort(statValue);
            }
            out.writeString(owner);
            out.writeShort(flags);
            switch (levelInfo) {
                case LevelInfo.CashPadding cashPadding -> {
                    for (int i = 0; i < 10; i++) {
                        out.writeByte(0x40);
                    }
                }
                case LevelInfo.Growth(var zero, var itemLevel, var expNibble, var vicious, var pad) -> {
                    out.writeByte(zero);
                    out.writeByte(itemLevel);
                    out.writeInt(expNibble);
                    out.writeInt(vicious);
                    out.writeLong(pad);
                }
            }
            out.writeLong(craftTime);
            out.writeInt(tail);
        }
    }

    /** 宠物块：13 字节定长名 + 成长面板 + 宠物到期（与公共头同值双写——两处本就同源） */
    record Pet(int itemId, boolean cash, long serial, long expiration, String name,
               byte level, short tameness, byte fullness, short attribute) implements ItemBlock {

        @Override
        public byte type() {
            return 3;
        }

        private void encodeBody(ByteBufBuilder out) {
            out.writeFixedString(name, 13);
            out.writeByte(level);
            out.writeShort(tameness);
            out.writeByte(fullness);
            out.writeLong(expiration);
            out.writeShort(attribute);
            out.writeShort(0); // PetSkill
            out.writeInt(18000); // RemainLife
            out.writeShort(0); // attribute
        }
    }

    /** 堆叠块：数量（可充值 = 可使用次数）+ owner/flags + 可充值魔数 */
    record Stack(int itemId, boolean cash, long serial, long expiration,
                 short quantity, String owner, int flags, boolean rechargeable) implements ItemBlock {

        @Override
        public byte type() {
            return 2;
        }

        private void encodeBody(ByteBufBuilder out) {
            out.writeShort(quantity);
            out.writeString(owner);
            out.writeShort(flags);
            if (rechargeable) {
                out.writeInt(2);
                out.writeBytes(new byte[]{(byte) 0x54, 0, 0, (byte) 0x34});
            }
        }
    }

    /** 装备成长段：现金物品 = 0x40 填充（无成长显示），其余 = 等级/经验 nibble */
    sealed interface LevelInfo {
        record CashPadding() implements LevelInfo {
        }

        record Growth(byte zero, byte itemLevel, int expNibble, int vicious, long pad) implements LevelInfo {
        }
    }
}
