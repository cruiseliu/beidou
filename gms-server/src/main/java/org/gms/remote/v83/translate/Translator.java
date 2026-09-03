package org.gms.remote.v83.translate;

import io.netty.buffer.ByteBuf;

import java.util.List;

/**
 * 翻译层出口：把本域积累的语义事件组装为 packet 树并编码为整帧 ByteBuf。
 * 分层职责见 gms-server/doc/package-client.md §6/§7。
 */
public interface Translator {
    boolean isEmpty();

    /** 组树 + 编码并清空内部缓冲；可能产出多帧（如背包满提示与操作包并存） */
    List<ByteBuf> flush();
}
