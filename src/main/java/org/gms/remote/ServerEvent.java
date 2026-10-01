package org.gms.remote;

/**
 * 语义事件的不可变载体：作用域期间语义调用的原始发生序记录（不做合并——合并是消费侧的事）。
 * 事件即意图，与"未开域逐条即时调用"共用同一组处理函数。
 * 携带可变实体的事件须为冻结快照，契约见 gms-server/doc/package-client.md §1。
 *
 * <p>S→C 方向的事件词表（版本不得伪造语义事件）；C→S 方向将另有独立层次。
 */
public interface ServerEvent extends ServerEventBase {}
