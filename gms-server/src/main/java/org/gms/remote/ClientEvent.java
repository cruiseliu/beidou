package org.gms.remote;

/**
 * C→S 语义事件的不可变载体基础接口：收包经版本解码/翻译得到，分派给对应模块的 In 入口
 * 事件 record 由各模块收包管线内部消费（decode 产物 → 模块 Handler 裸参数），不出 remote。
 */
public interface ClientEvent {}
