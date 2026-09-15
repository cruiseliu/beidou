package org.gms.remote.modules.npc.client;

/**
 * NPC 对话页的按钮布局语义（NpcModule.talk）。wire 映射（msgType/包尾按钮字节）
 * 归版本实现——本枚举只承载游戏语义，不得携带字节。
 */
public enum DialogButtons {
    /** 对话页 + 下一步 */
    NEXT,
    /** 对话页 + 上一步 */
    PREV_OK,
    /** 对话页 + 上一步/下一步 */
    PREV_NEXT,
    /** 对话页 + OK */
    OK,
    /** 是/否问句 */
    YES_NO,
    /** 接受/拒绝 */
    ACCEPT_DECLINE
}
