/**
 * bind_player.js 的类型声明（脚本目录无构建步骤，供 tsc --checkJs / IDE 消费；
 * 运行时 GraalJS 忽略本文件）。只声明脚本侧已消费的 API——其余导出按需补齐，
 * Java 真源在 org.gms.client.scripting.api.* 与 bind_player.js 本体。
 */

/**
 * DialogButtons 枚举名（org.gms.remote.modules.npc.client.DialogButtons 的 valueOf
 * 合法入参全集）。
 */
export type DialogButtonsName = "NEXT" | "PREV_OK" | "PREV_NEXT" | "OK" | "YES_NO" | "ACCEPT_DECLINE";

/**
 * ExpSource 枚举名（org.gms.client.character.ExpSource；随枚举演进扩充）。
 */
export type ExpSourceName = "QUEST";

/**
 * InteractContext 的 JS 视图（org.gms.client.scripting.InteractContext，player strand
 * 会话身份）。演出类 API 已外移（showInfo → message 分面）。
 */
export interface InteractContext {
    /** 终结会话（登记清除 + NPC 冷却）。 */
    dispose(): void;
}

/**
 * talk 分面（org.gms.client.scripting.api.TalkApi）：对话页渲染，npc 归会话上下文。
 */
export interface TalkApi {
    /** 统一对话页入口：buttons 为 DialogButtons 枚举名，Java 侧 valueOf 转换。 */
    send(ctx: InteractContext, text: string, buttons: DialogButtonsName): void;
}

/**
 * basic 分面（org.gms.client.scripting.api.BasicApi）：基础角色面。
 */
export interface BasicApi {
    isMale(): boolean;
    /** 经验入账；source 为 ExpSource 枚举名（Java 侧 valueOf 转换）。 */
    gainExp(gain: number, source: ExpSourceName): void;
}

/**
 * stats 分面（org.gms.client.scripting.api.StatsApi）：HP 状态面。
 */
export interface StatsApi {
    getHp(): number;
    /** 直接设 HP（绝对值语义，非增减）。 */
    updateHp(hp: number): void;
}

/**
 * inventory 分面（org.gms.client.scripting.api.InventoryApi）：背包道具面。
 */
export interface InventoryApi {
    hasItem(itemId: number): boolean;
    /** 发 1 件；背包满返回 false。 */
    gainItem(itemId: number): boolean;
    gainItem(itemId: number, quantity: number): boolean;
    /** 批量发 [itemId, 数量] 对；任一失败即整批失败并回滚。 */
    gainItems(entries: number[][]): boolean;
}

/**
 * quest 分面（org.gms.client.scripting.api.QuestApi）：任务状态面。
 */
export interface QuestApi {
    forceStartQuest(questId: number): boolean;
    forceCompleteQuest(questId: number): boolean;
}

/**
 * message 分面（org.gms.client.scripting.api.MessageApi）：提示消息面，解锁随语义拼装。
 */
export interface MessageApi {
    /** 屏幕上方提示条（教学指引等）。 */
    showHint(message: string, width: number, height: number): void;
    /** 过场 UI 图（item-inchat 帧发 WZ UI 路径）。 */
    showInfo(path: string): void;
}

/**
 * 脚本 API 显式范围（player actor，org.gms.client.scripting.api.PlayerApis 六分面）。
 */
export declare const player: {
    basic: BasicApi;
    stats: StatsApi;
    inventory: InventoryApi;
    talk: TalkApi;
    quest: QuestApi;
    message: MessageApi;
};

/**
 * 官方脚本文本表（String.wz/ScriptString/&lt;file&gt;.xml）：键带 $ 包裹原文，值为文本；
 * 文件缺失抛错（对白文本缺失不是合法运行态）。
 */
export declare function getScriptStrings(file: string): Map<string, string>;
