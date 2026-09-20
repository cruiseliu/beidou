package org.gms.remote;

/**
 * 语义域枚举（与 modules 树一一对应）：C→S 方向的域身份——ClientEvent 自报归属、
 * ClientEventDispatcher 按此查表找接收类。S→C 方向无此概念（owner 由产出 route 自声明）。
 */
public enum Module {
    BASIC,
    STATS,
    SKILLS,
    INVENTORY,
    PET,
    MAP,
    NPC,
    CASHSHOP,
    QUEST
}
