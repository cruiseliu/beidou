package org.gms.remote.gms083.client.translate;

import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * 收包 codec 契约（Packet 类静态 decode 的方法引用形态）：ByteBufReader → wire record。
 * 返回 null = 整包静默丢弃（空移动序列/未识别 command 等，现状语义）；其余异常炸出由
 * strand fail-safe 记日志。
 */
public interface InCodec<P> {

    P decode(ByteBufReader in);
}
