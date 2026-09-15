package org.gms.infra;

/**
 * 跨 actor 类型化消息基接口（闭包投递的替代面）：载荷自包含（不可变值 / 身份 ref /
 * 入域冻结快照），由发送方构造、投递到接收 actor 的 strand，在接收域内经
 * MessageDispatcher 分发执行。infra 不感知具体消息类型（非 sealed——子类型按域
 * 落在各 messages 包，依赖方向 infra ← 域）。
 */
public interface ActorMessage {

    /** 诊断名（队列日志/慢任务定位用）；默认取类简单名 */
    default String name() {
        return getClass().getSimpleName();
    }
}
