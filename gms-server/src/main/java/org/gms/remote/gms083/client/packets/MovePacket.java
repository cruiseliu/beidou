package org.gms.remote.gms083.client.packets;

import org.gms.exception.EmptyMovementException;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.modules.map.client.movement.AbsoluteMove;
import org.gms.remote.modules.map.client.movement.ChangeEquipMove;
import org.gms.remote.modules.map.client.movement.ChairMove;
import org.gms.remote.modules.map.client.movement.JumpDownMove;
import org.gms.remote.modules.map.client.movement.LegacyMove3;
import org.gms.remote.modules.map.client.movement.LegacyMove9;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.remote.modules.map.client.movement.RelativeMove;
import org.gms.remote.modules.map.client.movement.TeleportMove;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家移动包 codec（v83）：decode（bytes → 语义元素，纯函数）与中继编码（语义元素 → bytes）
 * 严格对称——byte buffer 不跨出本类（doc/13，round-trip 逐字节）。
 *
 * <p>command 布局与历史 parseMovement/updatePosition 并集对齐（含 11 椅子、14/21 保留布局）；
 * 未识别 command 抛 {@link EmptyMovementException}（现状语义，响亮失败）。
 */
public final class MovePacket {

    private MovePacket() {
    }

    /** 解码移动包语义元素序列（纯解码，无任何对象写入）。包头 9 字节由本方法跳过（包布局归 codec）。 */
    public static List<MoveElement> decode(InPacket p) throws EmptyMovementException {
        p.skip(9);
        byte numCommands = p.readByte();
        if (numCommands < 1) {
            throw new EmptyMovementException(p);
        }

        List<MoveElement> res = new ArrayList<>(numCommands);
        for (byte i = 0; i < numCommands; i++) {
            byte command = p.readByte();
            switch (command) {
                case 0, 5, 17 -> { // 绝对移动
                    int x = p.readShort();
                    int y = p.readShort();
                    int ppsX = p.readShort();
                    int ppsY = p.readShort();
                    int fh = p.readShort();
                    int stance = p.readByte();
                    int duration = p.readShort();
                    res.add(new AbsoluteMove(command, x, y, ppsX, ppsY, fh, stance, duration));
                }
                case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> { // 相对移动
                    int x = p.readShort();
                    int y = p.readShort();
                    int stance = p.readByte();
                    int duration = p.readShort();
                    res.add(new RelativeMove(command, x, y, stance, duration));
                }
                case 3, 4, 7, 8, 9 -> { // 瞬移/突进
                    int x = p.readShort();
                    int y = p.readShort();
                    int ppsX = p.readShort();
                    int ppsY = p.readShort();
                    int stance = p.readByte();
                    res.add(new TeleportMove(command, x, y, ppsX, ppsY, stance));
                }
                case 11 -> { // 椅子
                    int x = p.readShort();
                    int y = p.readShort();
                    int fh = p.readShort();
                    int stance = p.readByte();
                    int duration = p.readShort();
                    res.add(new ChairMove(command, x, y, fh, stance, duration));
                }
                case 15 -> { // 跳下
                    int x = p.readShort();
                    int y = p.readShort();
                    int ppsX = p.readShort();
                    int ppsY = p.readShort();
                    int fh = p.readShort();
                    int originFh = p.readShort();
                    int stance = p.readByte();
                    int duration = p.readShort();
                    res.add(new JumpDownMove(command, x, y, ppsX, ppsY, fh, originFh, stance, duration));
                }
                case 10 -> res.add(new ChangeEquipMove(p.readByte()));
                case 14 -> { // 保留布局：2+2+2+1+2
                    res.add(new LegacyMove9(command,
                            p.readShort(), p.readShort(), p.readShort(), p.readByte(), p.readShort()));
                }
                case 21 -> { // 保留布局：1+1+1
                    res.add(new LegacyMove3(command, p.readByte(), p.readByte(), p.readByte()));
                }
                default -> throw new EmptyMovementException(p);
            }
        }
        return res;
    }

    /** 移动中继包：MOVE_PLAYER + int charId + int 0 + 元素序列对称重放（与历史 PacketCreator.movePlayer 逐字节一致）。 */
    public static V83Packet relay(int charId, List<MoveElement> elements) {
        return new Relay(charId, List.copyOf(elements));
    }

    public record Relay(int charId, List<MoveElement> elements) implements V83Packet {

        @Override
        public SendOpcode opcode() {
            return SendOpcode.MOVE_PLAYER;
        }

        @Override
        public io.netty.buffer.ByteBuf encode() {
            io.netty.buffer.ByteBuf out = io.netty.buffer.Unpooled.buffer();
            out.writeShortLE(opcode().getValue());
            out.writeIntLE(charId);
            out.writeIntLE(0);
            out.writeByte(elements.size());
            for (MoveElement e : elements) {
                encodeElement(out, e);
            }
            return out;
        }

        private static void encodeElement(io.netty.buffer.ByteBuf out, MoveElement e) {
            switch (e) {
                case AbsoluteMove m -> {
                    out.writeByte(m.command());
                    out.writeShortLE(m.x());
                    out.writeShortLE(m.y());
                    out.writeShortLE(m.ppsX());
                    out.writeShortLE(m.ppsY());
                    out.writeShortLE(m.fh());
                    out.writeByte(m.stance());
                    out.writeShortLE(m.duration());
                }
                case RelativeMove m -> {
                    out.writeByte(m.command());
                    out.writeShortLE(m.x());
                    out.writeShortLE(m.y());
                    out.writeByte(m.stance());
                    out.writeShortLE(m.duration());
                }
                case TeleportMove m -> {
                    out.writeByte(m.command());
                    out.writeShortLE(m.x());
                    out.writeShortLE(m.y());
                    out.writeShortLE(m.ppsX());
                    out.writeShortLE(m.ppsY());
                    out.writeByte(m.stance());
                }
                case ChairMove m -> {
                    out.writeByte(m.command());
                    out.writeShortLE(m.x());
                    out.writeShortLE(m.y());
                    out.writeShortLE(m.fh());
                    out.writeByte(m.stance());
                    out.writeShortLE(m.duration());
                }
                case JumpDownMove m -> {
                    out.writeByte(m.command());
                    out.writeShortLE(m.x());
                    out.writeShortLE(m.y());
                    out.writeShortLE(m.ppsX());
                    out.writeShortLE(m.ppsY());
                    out.writeShortLE(m.fh());
                    out.writeShortLE(m.originFh());
                    out.writeByte(m.stance());
                    out.writeShortLE(m.duration());
                }
                case ChangeEquipMove m -> out.writeByte(m.equip());
                case LegacyMove9 m -> {
                    out.writeByte(m.command());
                    out.writeShortLE(m.f1());
                    out.writeShortLE(m.f2());
                    out.writeShortLE(m.f3());
                    out.writeByte(m.f4());
                    out.writeShortLE(m.f5());
                }
                case LegacyMove3 m -> {
                    out.writeByte(m.command());
                    out.writeByte(m.f1());
                    out.writeByte(m.f2());
                    out.writeByte(m.f3());
                }
            }
        }
    }
}
