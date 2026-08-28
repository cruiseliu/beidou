package org.gms.remote.v83.translate;

import io.netty.buffer.ByteBuf;

import java.util.List;

/**
 * 翻译层出口：把本域积累的语义事件组装为 packet 树并编码。
 * 上游可见：被 route 判定归属本域的 SemanticEvent 及 payload（含 ItemSlot 等 domain 对象）；
 * 下游可见：packet 层字段定义。输出整帧 ByteBuf（含 opcode 头）。
 */
public interface Translator {
    boolean isEmpty();

    /** 组树 + 编码并清空内部缓冲；可能产出多帧（如背包满提示与操作包并存） */
    List<ByteBuf> flush();
}
