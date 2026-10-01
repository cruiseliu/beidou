package org.gms.remote;

/**
 * 模块路由器（S→C，版本侧实现）：交付本模块的语义事件并负责本模块冲刷。
 * 与模块出脸（XxxModule）由同一版本类实现——route 一类四职：模块出脸 + freeze + deliver + flush。
 * 事件具体类型不出模块：deliver 只接收 ServerEventBase 宽类型，收窄发生在 route 内部
 * （只模式匹配自己的事件，由 dispatch 的 owner 标记保证）。
 */
public interface ServerEventDest {

    /** commit 回放：交付一条本模块事件（owner 标记保证归属） */
    void deliver(ServerEventBase r);

    /** 本模块冲刷（批量提交尾部统一调用；冲刷顺序由版本门面的业务序决定） */
    void flush();
}
