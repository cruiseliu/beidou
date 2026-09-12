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
        return decodeElements(p, numCommands, false);
    }

    /**
     * 元素序列解码（MOVE_PLAYER 与 MOVE_LIFE 共用；语法唯一分歧在 command 11：
     * 玩家 = 椅子（x/y/fh/stance/duration），life = 瞬移形（x/y/ppsX/ppsY/stance）——
     * 同为 9 字节，字段语义不同）。14/21 两侧都保留占位 record：updatePosition 对其
     * 无位置效果，但中继必须重放字节。
     */
    static List<MoveElement> decodeElements(InPacket p, byte numCommands, boolean lifeGrammar) throws EmptyMovementException {
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
                case 11 -> { // 椅子（玩家语法）/ 瞬移形（life 语法）
                    int x = p.readShort();
                    int y = p.readShort();
                    if (lifeGrammar) {
                        int ppsX = p.readShort();
                        int ppsY = p.readShort();
                        int stance = p.readByte();
                        res.add(new TeleportMove(command, x, y, ppsX, ppsY, stance));
                    } else {
                        int fh = p.readShort();
                        int stance = p.readByte();
                        int duration = p.readShort();
                        res.add(new ChairMove(command, x, y, fh, stance, duration));
                    }
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
            encodeElements(out, elements);
            return out;
        }

        static void encodeElement(io.netty.buffer.ByteBuf out, MoveElement e) {
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

    /** 元素序列对称重放（MOVE_PLAYER/MOVE_LIFE 中继共用；与历史 rebroadcastMovementList 的裸字节拷贝逐字节一致）。 */
    static void encodeElements(io.netty.buffer.ByteBuf out, List<MoveElement> elements) {
        out.writeByte(elements.size());
        for (MoveElement e : elements) {
            Relay.encodeElement(out, e);
        }
    }
}
