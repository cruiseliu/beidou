package org.gms.remote.v83.out.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;

import java.util.List;

/**
 * INVENTORY_OPERATION 封包树：槽位变更列表（纯字段，无外部对象）。
 * 数量为绝对终值（可充值 = 可使用次数，由 translate 层完成 charge→数量换算）；
 * addMovement 尾字节为派生值（moved/removed 出现负位=穿戴位，取末条结论），工厂计算。
 * 身份 = Item 引用（语义层已保证 Added.body 是冻结快照）。
 */
public record InventoryOperationPacket(boolean updateTick, List<Change> changes,
                                       Byte addMovement) implements V83Packet {

    public static InventoryOperationPacket of(boolean updateTick, List<Change> changes) {
        Byte addMovement = null;
        for (Change c : changes) {
            if (c instanceof Moved(var tab, var oldPos, var pos)) {
                if (pos < 0 || oldPos < 0) {
                    addMovement = oldPos < 0 ? (byte) 1 : (byte) 2;
                }
            } else if (c instanceof Removed(var tab, var pos)) {
                if (pos < 0) {
                    addMovement = 2;
                }
            }
        }
        return new InventoryOperationPacket(updateTick, List.copyOf(changes), addMovement);
    }

    /** 空操作帧 record（updateTick=true, count=0）：背包满信号的第一帧（与 0xff 状态包成对） */
    public static InventoryOperationPacket empty() {
        return of(true, List.of());
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.INVENTORY_OPERATION;
    }

    @Override
    public ByteBuf encode() {
        ByteBuf out = Unpooled.buffer();
        out.writeShortLE(opcode().getValue());
        out.writeBoolean(updateTick);
        out.writeByte(changes.size());
        int addMovement = -1;
        for (Change c : changes) {
            if (c instanceof Added(var tab, var pos, var body)) {
                writeHeader(out, (byte) 0, tab, pos);
                writeBody(out, body);
            } else if (c instanceof QuantityUpdated(var tab, var pos, var quantity)) {
                writeHeader(out, (byte) 1, tab, pos);
                out.writeShortLE(quantity);
            } else if (c instanceof Moved(var tab, var oldPos, var pos)) {
                writeHeader(out, (byte) 2, tab, oldPos);
                out.writeShortLE(pos);
                if (pos < 0 || oldPos < 0) {
                    addMovement = oldPos < 0 ? 1 : 2;
                }
            } else if (c instanceof Removed(var tab, var pos)) {
                writeHeader(out, (byte) 3, tab, pos);
                if (pos < 0) {
                    addMovement = 2;
                }
            }
        }
        if (addMovement > -1) {
            out.writeByte(addMovement);
        }
        return out;
    }

    private static void writeHeader(ByteBuf out, byte mode, byte tab, short pos) {
        out.writeByte(mode);
        out.writeByte(tab);
        out.writeShortLE(pos);
    }

    private static void writeBody(ByteBuf out, ItemBody body) {
        out.writeByte(body.type());
        out.writeIntLE(body.itemId());
        out.writeBoolean(body.cash());
        if (body.cash()) {
            out.writeLongLE(body.serial());
        }
        out.writeLongLE(body.expiration());
        switch (body) {
            case ItemBody.Equip equip -> {
                out.writeByte(equip.tuc());
                out.writeByte(equip.level());
                for (short statValue : equip.stats()) {
                    out.writeShortLE(statValue);
                }
                writeLengthString(out, equip.owner());
                out.writeShortLE(equip.flags());
                switch (equip.levelInfo()) {
                    case LevelInfo.CashPadding cashPadding -> {
                        for (int i = 0; i < 10; i++) {
                            out.writeByte(0x40);
                        }
                    }
                    case LevelInfo.Growth(var zero, var itemLevel, var expNibble, var vicious, var pad) -> {
                        out.writeByte(zero);
                        out.writeByte(itemLevel);
                        out.writeIntLE(expNibble);
                        out.writeIntLE(vicious);
                        out.writeLongLE(pad);
                    }
                }
                out.writeLongLE(equip.craftTime());
                out.writeIntLE(equip.tail());
            }
            case ItemBody.Pet pet -> {
                writeFixedBytes(out, pet.name(), 13);
                out.writeByte(pet.level());
                out.writeShortLE(pet.tameness());
                out.writeByte(pet.fullness());
                out.writeLongLE(pet.expiration());
                out.writeShortLE(pet.attribute());
                out.writeShortLE(0); // PetSkill
                out.writeIntLE(18000); // RemainLife
                out.writeShortLE(0); // attribute
            }
            case ItemBody.Stack stack -> {
                out.writeShortLE(stack.quantity());
                writeLengthString(out, stack.owner());
                out.writeShortLE(stack.flags());
                if (stack.rechargeable()) {
                    out.writeIntLE(2);
                    out.writeBytes(new byte[]{(byte) 0x54, 0, 0, (byte) 0x34});
                }
            }
        }
    }

    private static void writeLengthString(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeShortLE(bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeFixedBytes(ByteBuf out, byte[] value, int fixed) {
        byte[] fixedBytes = java.util.Arrays.copyOf(value, fixed);
        out.writeBytes(fixedBytes);
    }

    public sealed interface Change {
    }

    public record Added(byte tab, short pos, ItemBody body) implements Change {
    }

    public record QuantityUpdated(byte tab, short pos, short quantity) implements Change {
    }

    public record Moved(byte tab, short oldPos, short pos) implements Change {
    }

    public record Removed(byte tab, short pos) implements Change {
    }

    /** 物品体：三分支共享公共头（type/id/cash/序列号/过期）；数量/穿戴属性在分支内 */
    public sealed interface ItemBody {
        byte type();

        int itemId();

        boolean cash();

        long serial();

        long expiration();

        record Equip(int itemId, boolean cash, long serial, long expiration,
                     byte tuc, byte level, short[] stats, String owner, int flags,
                     LevelInfo levelInfo, long craftTime, int tail) implements ItemBody {
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
        }

        record Pet(int itemId, boolean cash, long serial, long expiration, byte[] name,
                   byte level, short tameness, byte fullness, short attribute) implements ItemBody {
            @Override
            public byte type() {
                return 3;
            }
        }

        record Stack(int itemId, boolean cash, long serial, long expiration,
                     short quantity, String owner, int flags, boolean rechargeable) implements ItemBody {
            @Override
            public byte type() {
                return 2;
            }
        }
    }

    public sealed interface LevelInfo {
        record CashPadding() implements LevelInfo {
            public static final CashPadding INSTANCE = new CashPadding();
        }

        record Growth(byte zero, byte level, int expNibble, int vicious, long pad) implements LevelInfo {
        }
    }
}
