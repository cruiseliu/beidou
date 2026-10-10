package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.server.blocks.MonsterBlock;
import org.gms.remote.gms083.utils.ByteBufBuilder;
import org.gms.constants.string.CharsetConstants;

/**
 * SPAWN_MONSTER_CONTROL 的授控形态（0x01/0x02 头）：帧 = mode + {@link MonsterBlock}
 * 条目体，与历史 PacketCreator.controlMonster → spawnMonsterInternal(control=true) 逐字节一致。
 * 全部决策字段已在 freeze 层固化，encode = 纯字段重放。假怪形态（kind=5，{@link MonsterBlock.Fake}）
 * 同帧头，共用本 record。stop 形态（0x00）归 P3 收编，不在本 record。
 *
 * @param mode  1 = 普通 grant/假怪，2 = aggro grant（legacy: aggro ? 2 : 1）
 */
public record ControlMonsterPacket(byte mode, MonsterBlock block) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SPAWN_MONSTER_CONTROL;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder out = new ByteBufBuilder(CharsetConstants.getCharset(0));
        out.writeShort(opcode().getValue());
        out.writeByte(mode);
        block.encodeBody(out);
        return Unpooled.wrappedBuffer(out.getBytes());
    }
}
