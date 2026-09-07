package org.gms.remote;

/**
 * 角色actor 的收包入口聚合：按模块暴露 In 面（各模块接口的嵌套子接口，实现方为 gameplay
 * 组件）。由 Character 装配（组件 wiring 的唯一落点）；v83.in 管线经它分派事件，
 * 分派表见 {@link org.gms.remote.in.ModuleInDispatch}。
 */
public interface ModuleIn {

    PetModule.In pet();

    InventoryModule.In inventory();
}
