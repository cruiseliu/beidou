package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

import java.awt.Point;
import java.util.List;

/**
 * SPAWN_MONSTER_CONTROL 的 grant 形态（0x01/0x02 头）：授控全身帧，
 * 与历史 PacketCreator.controlMonster → spawnMonsterInternal(control=true) 逐字节一致。
 * 全部决策字段（mode/controllerKind/stati 条目与顺序/父怪关联/反击尾）已在 freeze 层固化，
 * encode = 纯字段重放。stop 形态（0x00）归 P3 收编，不在本 record。
 *
 * @param mode             1 = 普通 grant，2 = aggro grant（legacy: aggro ? 2 : 1）
 * @param controllerKind   legacy: controller == null ? 5 : 1（grant 语境恒 1，freeze 活读固化）
 * @param statuses         stati 条目（顺序 = legacy 过滤后 HashMap 迭代序；空表 = 16 字节零掩码）
 * @param mask             4 × int 状态位掩码（isFirst → 前两段，否则后两段）
 * @param reflectTail      反击尾（pCounter/mCounter 哨兵 -1 = 不出现）
 * @param linkedParentOid  父怪关联：>0 = writeByte(-3)+writeInt(oid)；0 = writeByte(-1)
 */
public record ControlMonsterPacket(
        byte mode, int oid, byte controllerKind, int mobId,
        List<StatusEntry> statuses, int[] mask, ReflectTail reflectTail,
        Point position, byte stance, short fh, byte team,
        int linkedParentOid) implements V83Packet {

    /**
     * 单条 stati 条目（legacy encodeTemporary 循环体的值化）：
     * fromMobSkill = true → writeShort(msType)+writeShort(msLevel)；false → writeInt(skillId)。
     */
    public record StatusEntry(short value, boolean fromMobSkill, short msType, short msLevel, int skillId) {
    }

    /** 反击尾部（legacy pCounter/mCounter 哨兵逻辑）：pCounter 或 mCounter 命中时追加计数与 100 概率 */
    public record ReflectTail(int pCounter, int mCounter) {
        public boolean present() {
            return pCounter != -1 || mCounter != -1;
        }

        public static ReflectTail none() {
            return new ReflectTail(-1, -1);
        }
    }

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SPAWN_MONSTER_CONTROL;
    }

    @Override
    public ByteBuf encode() {
        OutPacket p = OutPacket.create(SendOpcode.SPAWN_MONSTER_CONTROL);
        p.writeByte(mode);
        p.writeInt(oid);
        p.writeByte(controllerKind);
        p.writeInt(mobId);
        encodeTemporary(p);
        p.writePos(position);
        p.writeByte(stance);
        p.writeShort(0); //Origin FH //life.getStartFh()
        p.writeShort(fh);
        if (linkedParentOid > 0) {
            p.writeByte(-3);
            p.writeInt(linkedParentOid);
        } else {
            p.writeByte(-1);    // encodeParentlessMobSpawnEffect(effect=0, newSpawn=false)
        }
        p.writeByte(team);
        p.writeInt(0); // getItemEffect
        return Unpooled.wrappedBuffer(p.getBytes());
    }

    /** legacy encodeTemporary 的字段重放（掩码 → 条目 → 反击尾） */
    private void encodeTemporary(OutPacket p) {
        for (int m : mask) {
            p.writeInt(m);
        }
        for (StatusEntry e : statuses) {
            p.writeShort(e.value());
            if (e.fromMobSkill()) {
                p.writeShort(e.msType());
                p.writeShort(e.msLevel());
            } else {
                p.writeInt(e.skillId());
            }
            p.writeShort(-1);    // duration
        }
        if (reflectTail.pCounter() != -1) {
            p.writeInt(reflectTail.pCounter());// wPCounter_
        }
        if (reflectTail.mCounter() != -1) {
            p.writeInt(reflectTail.mCounter());// wMCounter_
        }
        if (reflectTail.present()) {
            p.writeInt(100);// nCounterProb_
        }
    }
}
