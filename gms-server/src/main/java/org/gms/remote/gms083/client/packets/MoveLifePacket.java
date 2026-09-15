package org.gms.remote.gms083.client.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.exception.EmptyMovementException;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.remote.gms083.server.packets.V83Packet;
import org.gms.remote.modules.map.client.MoveLife;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.awt.*;
import java.util.List;

/**
 * MOVE_LIFE codec（v83）：controller 客户端的 mob 移动汇报——语义头部 + 移动元素序列。
 * 元素语法与 MOVE_PLAYER 共用（唯一分歧 command 11，见 MovePlayerPacket#decodeElements），
 * 位置应用语义（updatePosition 的 monster 分支）归地图域，不在此层。
 */
public final class MoveLifePacket {

    private MoveLifePacket() {
    }

    /**
     * 解码语义头部 + 元素序列（纯解码；包布局归本类）。rawActivity 保留原样字节——
     * 活动/技能判定与攻击门控改写它，属 gameplay 语义，在地图域 verbatim 进行。
     */
    public static MoveLife decode(InPacket p) throws EmptyMovementException {
        int oid = p.readInt();
        short moveid = p.readShort();
        byte pNibbles = p.readByte();
        byte rawActivity = p.readByte();
        int skillId = p.readByte() & 0xff;
        int skillLv = p.readByte() & 0xff;
        short pOption = p.readShort();
        p.skip(8);
        p.readByte();   // 未知字节（历史 handler 原样消费）
        p.readInt();    // whatever
        short startX = p.readShort();
        short startY = p.readShort();
        Point startPos = new Point(startX, startY - 2);

        byte numCommands = p.readByte();
        if (numCommands < 1) {
            throw new EmptyMovementException(p);
        }
        List<MoveElement> elements = MovePlayerPacket.decodeElements(p, numCommands, true);
        return new MoveLife(oid, moveid, pNibbles, rawActivity, skillId, skillLv, pOption, startPos, elements);
    }

    /**
     * controller 的移动 ack（与历史 PacketCreator.moveMonsterResponse 逐字节一致）。
     * 载荷依赖 mob 状态（mobMp/aggro/下次技能），在地图域编码。
     */
    public record Response(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel)
            implements V83Packet {

        @Override
        public SendOpcode opcode() {
            return SendOpcode.MOVE_MONSTER_RESPONSE;
        }

        @Override
        public ByteBuf encode() {
            ByteBuf out = Unpooled.buffer();
            out.writeShortLE(opcode().getValue());
            out.writeIntLE(oid);
            out.writeShortLE(moveid);
            out.writeByte(useSkills ? 1 : 0);
            out.writeShortLE(currentMp);
            out.writeByte(skillId);
            out.writeByte(skillLevel);
            return out;
        }
    }

    /** 他人流中继（与历史 PacketCreator.moveMonster 逐字节一致；元素序列对称重放）。 */
    public record Relay(int oid, boolean skillPossible, int skill, int skillId, int skillLevel, int pOption,
                        Point startPos, List<MoveElement> elements) implements V83Packet {

        @Override
        public SendOpcode opcode() {
            return SendOpcode.MOVE_MONSTER;
        }

        @Override
        public ByteBuf encode() {
            ByteBuf out = Unpooled.buffer();
            out.writeShortLE(opcode().getValue());
            out.writeIntLE(oid);
            out.writeByte(0);
            out.writeByte(skillPossible ? 1 : 0);
            out.writeByte(skill);
            out.writeByte(skillId);
            out.writeByte(skillLevel);
            out.writeShortLE(pOption);
            out.writeShortLE((short) startPos.getX());
            out.writeShortLE((short) startPos.getY());
            MovePlayerPacket.encodeElements(out, elements);
            return out;
        }
    }
}
