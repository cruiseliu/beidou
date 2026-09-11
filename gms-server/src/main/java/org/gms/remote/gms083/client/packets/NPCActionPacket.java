package org.gms.remote.gms083.client.packets;

import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;

/**
 * NPC_ACTION 回声 codec（v83）：客户端动画状态机 loopback——服务端对内容零语义消费，
 * 原样回发（byte 封装在本类内，不外泄，doc/13）。
 *
 * <p>wire 信封（服务端已知全貌）：available==6 → "talk"（int + byte + byte，字段含义未解释）；
 * available&gt;6 → 9 字节包头跳过 + 变长余部原样拷贝；available&lt;6 → 仅 opcode 的空包
 * （现状怪癖，原样保留）。
 *
 * <p>FIXME(NPC_ACTION 广播)：官方服务器任务完成时 NPC 会对全图做特定表情（NPC_ACTION 的
 * 广播形态），私服缺失该广播导致表现缺失。与本回声无关——待"服务端消费 NPC 动画/表情"
 * 的需求成立时（语义化逆向 + 广播拓扑）一并处理。
 */
public final class NPCActionPacket {

    private NPCActionPacket() {
    }

    /** 回声：按现状逻辑原样复制（talk 分支逐字段转发 / move 分支跳 9 拷贝余部）。 */
    public static Packet echo(InPacket p) {
        OutPacket op = OutPacket.create(SendOpcode.NPC_ACTION);
        int length = p.available();
        if (length == 6) { // NPC Talk
            op.writeInt(p.readInt());
            op.writeByte(p.readByte());
            op.writeByte(p.readByte());
        } else if (length > 6) { // NPC Move
            op.writeBytes(p.readBytes(length - 9));
        }
        return op;
    }
}
