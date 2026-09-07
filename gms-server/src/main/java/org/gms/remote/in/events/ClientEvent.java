package org.gms.remote.in.events;

/**
 * C→S 语义事件的不可变载体基础接口：收包经版本解码/翻译得到，分派给对应模块的 In 入口
 * （见 {@code org.gms.remote.ModuleInDispatch}）。与 S→C 方向的 SemanticEvent 对称。
 */
public sealed interface ClientEvent permits SummonPetEvent, UseItemEvent {
}
