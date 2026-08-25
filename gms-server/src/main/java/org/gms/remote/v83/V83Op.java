package org.gms.remote.v83;

import org.gms.client.Client;

/** 一个 opcode 的待发编码器：持有合并队列，sendTo 编码发送并清空（由 V83RemoteClient 路由调用） */
interface V83Op {
    boolean isEmpty();

    void sendTo(Client client);
}
