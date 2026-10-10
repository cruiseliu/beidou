package org.gms.remote.gms083.server.blocks;

import org.gms.remote.gms083.utils.ByteBufBuilder;

import java.awt.Point;
import java.util.List;

/**
 * 怪物落地/授控 wire 块（v83）：SPAWN_MONSTER 与 SPAWN_MONSTER_CONTROL 的共用编码单元
 * （{@link ItemBlock} 的怪物对偶）。字段 = 基础类型（构造时固化）；encode 纯字段重放——
 * opcode 与头部字节（授控 mode / 假怪 kind=5）归各包帧结构，本块只编码 oid 起的条目体：
 * oid + controllerKind + mobId + stati 段 + 位置/朝向/fh + 演出段 + team + 尾 int。
 *
 * <p>语义查表全部在工厂（freeze）完成：stati 过滤（WATK/WDEF）与迭代序、位掩码分段
 * （isFirst → 前两段）、反击哨兵、父怪关联判定（活查 map）。
 */
public sealed interface MonsterBlock {

    int oid();

    byte controllerKind();

    int mobId();

    Point position();

    byte stance();

    short fh();

    byte team();

    /** 条目体编码：oid 起至尾 int（stati 段与演出段为变体钩子） */
    default void encodeBody(ByteBufBuilder out) {
        out.writeInt(oid());
        out.writeByte(controllerKind());
        out.writeInt(mobId());
        encodeStati(out);
        out.writePos(position());
        out.writeByte(stance());
        out.writeShort(0); //Origin FH //life.getStartFh()
        out.writeShort(fh());
        encodeEffect(out);
        out.writeByte(team());
        out.writeInt(0); //getItemEffect
    }

    /** stati 段：授控/假怪 = encodeTemporary 全量，落地帧 = 固定 16 字节跳过 */
    void encodeStati(ByteBufBuilder out);

    /** 演出段：父怪关联 / 淡入 / 特演，各变体不同 */
    void encodeEffect(ByteBufBuilder out);

    /** 单条 stati 条目（legacy encodeTemporary 循环体的值化）：
     * fromMobSkill = true → writeShort(msType)+writeShort(msLevel)；false → writeInt(skillId)。 */
    record StatusEntry(short value, boolean fromMobSkill, short msType, short msLevel, int skillId) {
    }

    /** 反击尾部（legacy pCounter/mCounter 哨兵逻辑）：命中任一 → 追加计数与 100 概率 */
    record ReflectTail(int pCounter, int mCounter) {
        public boolean present() {
            return pCounter != -1 || mCounter != -1;
        }

        public static ReflectTail none() {
            return new ReflectTail(-1, -1);
        }
    }

    /** temporary stati 提取产物（freeze 层产出，Control/Fake 帧共用） */
    record Stati(List<StatusEntry> statuses, int[] mask, ReflectTail tail) {
    }

    /** 授控块：encodeTemporary 全量（掩码 → 条目 → 反击尾）；演出段 = 父怪关联(-3)或 -1 */
    record Control(int oid, byte controllerKind, int mobId, List<StatusEntry> statuses, int[] mask,
                   ReflectTail tail, Point position, byte stance, short fh, byte team,
                   int linkedParentOid) implements MonsterBlock {

        @Override
        public void encodeStati(ByteBufBuilder out) {
            MonsterBlock.encodeStatiOf(out, mask, statuses, tail);
        }

        @Override
        public void encodeEffect(ByteBufBuilder out) {
            if (linkedParentOid > 0) {
                out.writeByte(-3);
                out.writeInt(linkedParentOid);
            } else {
                out.writeByte(-1);    // encodeParentlessMobSpawnEffect(effect=0, newSpawn=false)
            }
        }
    }

    /** 落地块：stati 恒 16 字节跳过；演出段 = 父怪关联(-3)或 parentless（淡入/特演/-1） */
    record Spawn(int oid, byte controllerKind, int mobId, Point position, byte stance, short fh, byte team,
                 boolean newSpawn, int effect, int linkedParentOid) implements MonsterBlock {

        @Override
        public void encodeStati(ByteBufBuilder out) {
            for (int i = 0; i < 16; i++) {   // legacy p.skip(16)
                out.writeByte(0);
            }
        }

        @Override
        public void encodeEffect(ByteBufBuilder out) {
            if (linkedParentOid > 0) {
                out.writeByte(-3);
                out.writeInt(linkedParentOid);
            } else {
                encodeParentless(out, newSpawn(), effect());
            }
        }
    }

    /** 假怪块（帧 = CONTROL 头 mode 1 + kind 5）：temporary stati；演出段 = effect 段(if>0) + -2 */
    record Fake(int oid, int mobId, List<StatusEntry> statuses, int[] mask, ReflectTail tail,
                Point position, byte stance, short fh, byte team, int effect) implements MonsterBlock {

        @Override
        public byte controllerKind() {
            return 5;    // legacy spawnFakeMonster 恒 5
        }

        @Override
        public void encodeStati(ByteBufBuilder out) {
            MonsterBlock.encodeStatiOf(out, mask, statuses, tail);
        }

        @Override
        public void encodeEffect(ByteBufBuilder out) {
            if (effect() > 0) {
                out.writeByte(effect());
                out.writeByte(0);
                out.writeShort(0);
            }
            out.writeShort(-2);
        }
    }

    private static void encodeParentless(ByteBufBuilder out, boolean newSpawn, int effect) {
        if (effect > 0) {
            out.writeByte(effect);
            out.writeByte(0);
            out.writeShort(0);
            if (effect == 15) {
                out.writeByte(0);
            }
        }
        out.writeByte(newSpawn ? -2 : -1);
    }

    /** temporary stati 编码的共享实现（Control 与 Fake 同构） */
    private static void encodeStatiOf(ByteBufBuilder out, int[] mask, List<StatusEntry> statuses,
                                      ReflectTail tail) {
        for (int m : mask) {
            out.writeInt(m);
        }
        for (StatusEntry e : statuses) {
            out.writeShort(e.value());
            if (e.fromMobSkill()) {
                out.writeShort(e.msType());
                out.writeShort(e.msLevel());
            } else {
                out.writeInt(e.skillId());
            }
            out.writeShort(-1);    // duration
        }
        if (tail.pCounter() != -1) {
            out.writeInt(tail.pCounter());// wPCounter_
        }
        if (tail.mCounter() != -1) {
            out.writeInt(tail.mCounter());// wMCounter_
        }
        if (tail.present()) {
            out.writeInt(100);// nCounterProb_
        }
    }
}
