package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.constants.string.CharsetConstants;
import org.gms.net.opcodes.SendOpcode;
import org.gms.remote.gms083.server.blocks.MonsterBlock;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * SPAWN_MONSTER（落地广播形态，无 mode 头）：帧 = opcode + {@link MonsterBlock.Spawn}
 * 条目体，与历史 PacketCreator.spawnMonster → spawnMonsterInternal(control=false) 逐字节一致。
 * stati 段恒 16 字节跳过；演出段（淡入/特演/父怪关联）已在 freeze 层固化。
 */
public record SpawnMonsterPacket(MonsterBlock.Spawn block) implements V83Packet {

    @Override
    public SendOpcode opcode() {
        return SendOpcode.SPAWN_MONSTER;
    }

    @Override
    public ByteBuf encode() {
        ByteBufBuilder out = new ByteBufBuilder(CharsetConstants.getCharset(0));
        out.writeShort(opcode().getValue());
        block.encodeBody(out);
        return Unpooled.wrappedBuffer(out.getBytes());
    }
}
