package org.gms.remote;

/**
 * C→S 语义事件的不可变载体基础接口：收包经版本 codec 解码 + translator 翻译得到，
 * 由 {@link ClientEventDispatcher} 按事件自报的 {@link #module() module} 查表分派给
 * 对应模块的接收类（XxxInbound），再由接收类解包直调模块 Handler 裸参数入口。
 * 事件 record 不出 remote。事件归属域与产出它的 router 无关——router 只负责把包
 * 翻译成事件，事件归谁消费由事件自己的 {@link #module()} 声明。
 */
public interface ClientEvent {

    /** 事件归属的语义域（dispatcher 查表键；声明与接收分支不一致 = 语义 bug，响亮失败） */
    Module module();
}
