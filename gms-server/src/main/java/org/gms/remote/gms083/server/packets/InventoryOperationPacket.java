package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.constants.string.CharsetConstants;
import org.gms.remote.gms083.server.blocks.ItemBlock;
import org.gms.remote.gms083.utils.ByteBufBuilder;
import org.gms.net.opcodes.SendOpcode;

import java.util.List;

/**
 * INVENTORY_OPERATION 封包树：槽位变更列表（纯字段，无外部对象）。
 * 数量为绝对终值（可充值 = 可使用次数，由 ItemBlock.of 完成 charge→数量换算）；
 * addMovement 尾字节为派生值（moved/removed 出现负位=穿戴位，取末条结论），工厂计算。
 * 条目体 = {@link ItemBlock}（语义层已保证 Added 的块是冻结快照）；builder 取
 * lang-0 charset——条目内的 owner 等字符串按其编码。
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
        ByteBufBuilder out = new ByteBufBuilder(CharsetConstants.getCharset(0));
        out.writeShort(opcode().getValue());
        out.writeBool(updateTick);
        out.writeByte(changes.size());
        int addMovement = -1;
        for (Change c : changes) {
            if (c instanceof Added(var tab, var pos, var block)) {
                writeHeader(out, (byte) 0, tab, pos);
                block.encode(out);
            } else if (c instanceof QuantityUpdated(var tab, var pos, var quantity)) {
                writeHeader(out, (byte) 1, tab, pos);
                out.writeShort(quantity);
            } else if (c instanceof Moved(var tab, var oldPos, var pos)) {
                writeHeader(out, (byte) 2, tab, oldPos);
                out.writeShort(pos);
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
        return out.build();
    }

    private static void writeHeader(ByteBufBuilder out, byte mode, byte tab, short pos) {
        out.writeByte(mode);
        out.writeByte(tab);
        out.writeShort(pos);
    }

    public sealed interface Change {
    }

    public record Added(byte tab, short pos, ItemBlock block) implements Change {
    }

    public record QuantityUpdated(byte tab, short pos, short quantity) implements Change {
    }

    public record Moved(byte tab, short oldPos, short pos) implements Change {
    }

    public record Removed(byte tab, short pos) implements Change {
    }
}
