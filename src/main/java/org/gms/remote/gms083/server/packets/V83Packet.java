package org.gms.remote.gms083.server.packets;

import io.netty.buffer.ByteBuf;
import org.gms.net.opcodes.SendOpcode;

/**
 * v83 packet record 的共通能力：可读 opcode + 自编码。
 * 一个 opcode 一个 record（形态分化用嵌套 sealed body）；编码本体即
 * {@link #encode()} 实例方法，opcode 前缀由实现保证写出。
 * 发送侧统一经 route 层 encode + 日志（见 V83RemoteClient.send/wire）。
 */
public interface V83Packet {

    SendOpcode opcode();

    ByteBuf encode();
}
