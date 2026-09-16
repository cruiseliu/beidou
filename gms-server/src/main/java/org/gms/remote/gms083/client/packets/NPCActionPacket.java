package org.gms.remote.gms083.client.packets;

import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.Packet;
import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * NPC_ACTION 回声 codec（v83）：客户端动画状态机 loopback——服务端对内容零语义消费，
 * 原样回发（byte 封装在本类内，不外泄，doc/13）。
 *
 * <p>wire 信封（服务端已知全貌）：available==6 → "talk"（int + byte + byte，字段含义未解释）；
 * available&gt;6 → 跳过尾部 9 字节、余部原样拷贝；available&lt;6 → 仅 opcode 的空包
 * （现状怪癖，原样保留）。三分支在 {@link #decode} 内解析为回发载荷，byte 不出本类。
 *
 * <p>FIXME(NPC_ACTION 广播)：官方服务器任务完成时 NPC 会对全图做特定表情（NPC_ACTION 的
 * 广播形态），私服缺失该广播导致表现缺失。与本回声无关——待"服务端消费 NPC 动画/表情"
 * 的需求成立时（语义化逆向 + 广播拓扑）一并处理。
 */
public final class NPCActionPacket {

    private NPCActionPacket() {
    }

    /** 回声包解码产物：回发载荷（opcode 后内容，按现状分支逻辑原样复制）。 */
    public record Echo(byte[] payload) {
    }

    /** 解码：按信封长度分支提取回发载荷（talk 分支逐字段 / move 分支拷余部 / 短包 = 空）。 */
    public static Echo decode(ByteBufReader p) {
        int length = p.available();
        if (length == 6) { // NPC Talk
            return new Echo(p.readBytes(6));
        } else if (length > 6) { // NPC Move
            return new Echo(p.readBytes(length - 9));
        }
        return new Echo(new byte[0]);
    }

    /** 回声：按现状逻辑原样复制（与历史 PacketCreator 形态逐字节一致）。 */
    public static Packet echo(Echo e) {
        OutPacket op = OutPacket.create(SendOpcode.NPC_ACTION);
        op.writeBytes(e.payload());
        return op;
    }
}
