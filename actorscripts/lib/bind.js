/*
 * 脚本通用 API 门面（ESM）。Java 交互集中在此，道具脚本只面对 JS 惯用 API。
 * 契约与 scripts/item/coupon.js 文件头一致；实现项随背包重构步骤逐个补齐（doc/10）。
 */

// ── 已实现 ──

const Server = Java.type("org.gms.net.server.Server");
const ScriptTimers = Java.type("org.gms.scripting.ScriptTimers");
const TimeZone = Java.type("java.util.TimeZone");
const RateBucketEnum = Java.type("org.gms.client.character.RateBucket");
const ExpSourceEnum = Java.type("org.gms.client.character.ExpSource");
const WzJsonConverter = Java.type("org.gms.scripting.WzJsonConverter");
const DataProviderFactory = Java.type("org.gms.provider.DataProviderFactory");
const WZFiles = Java.type("org.gms.provider.wz.WZFiles");
const ItemRegistry = Java.type("org.gms.client.inventory.ItemRegistry");
const I18nUtil = Java.type("org.gms.util.I18nUtil");

// provider 惰性取用：WZFiles 初始化需要 Spring 容器（wz 语言配置），不在模块装载期触碰
let itemDataProvider = null;
let scriptStringProvider = null;

function getItemDataProvider() {
    if (itemDataProvider == null) {
        itemDataProvider = DataProviderFactory.getDataProvider(WZFiles.ITEM);
    }
    return itemDataProvider;
}

function getScriptStringProvider() {
    if (scriptStringProvider == null) {
        scriptStringProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
    }
    return scriptStringProvider;
}

/** wz 道具数据缓存：itemId → 整树 JS 对象 | null（wz 数据不可变，永不失效；含缺失 id 负缓存） */
const itemDataCache = new Map();

/** itemId → Item.wz 节点（wz 惯例：img 目录名 = "0"+id 前 4 位，节点名 = "0"+id 8 位补零） */
function wzItemNode(itemId) {
    const idStr = "0" + itemId;
    const data = getItemDataProvider();
    for (const dir of data.getRoot().getSubdirectories()) {
        for (const file of dir.getFiles()) {
            if (file.getName() === idStr.substring(0, 4) + ".img"
                || file.getName() === idStr.substring(1) + ".img") {
                const img = data.getData(dir.getName() + "/" + file.getName());
                return img == null ? null : img.getChildByPath(idStr);
            }
        }
    }
    return null;
}

/** 服务器本地墙钟时间戳：全服统一时钟 + 服务器时区偏移（时区由 Server 启动时按配置统一设定） */
export function getServerLocalTime() {
    const now = Server.getInstance().getCurrentTime();
    return now + TimeZone.getDefault().getOffset(now);
}

/** 延时后在脚本会话内串行执行 fn，返回定时器 id */
export function setTimeout(fn, delayMs) {
    return ScriptTimers.setTimeout(fn, delayMs);
}

/** 取消定时器；未知/已触发 id 静默 */
export function clearTimeout(id) {
    ScriptTimers.clearTimeout(id);
}

/** 倍率贡献桶枚举（Java enum 原生透传，RateBucket.ITEM） */
export const RateBucket = RateBucketEnum;

/** 经验来源枚举（Java enum 原生透传，ExpSource.QUEST——gainExp 演出形态由此决定） */
export const ExpSource = ExpSourceEnum;

/** wz 道具数据（整树 JS 对象：imgdir → 对象、标量原样、画布/向量省略）；无该物品 null */
export function getWzItemData(itemId) {
    if (itemDataCache.has(itemId)) {
        return itemDataCache.get(itemId);
    }
    const node = wzItemNode(itemId);
    const data = node == null ? null : JSON.parse(String(WzJsonConverter.convert(node)));
    itemDataCache.set(itemId, data);
    return data;
}

/** 道具定义（data/item/*.json[c] 条目原文经 JSON.parse，字段集 = 数据文件本身，
 *  Java 侧只消费 itemId/hooks，其余字段是脚本载荷，缺失即 undefined）；未登记道具 null */
const itemDefCache = new Map();

export function getItemDefinition(itemId) {
    if (itemDefCache.has(itemId)) {
        return itemDefCache.get(itemId);
    }
    const raw = ItemRegistry.rawJson(itemId);
    const def = raw == null ? null : JSON.parse(raw);
    itemDefCache.set(itemId, def);
    return def;
}

/** ScriptString 文件 → 原始键表（wz 数据不可变，永不失效） */
const scriptStringsCache = new Map();

/**
 * 官方脚本文本表（String.wz/ScriptString/&lt;file&gt;.xml 的 string 条目原文）：
 * Map&lt;键原文, 文本&gt;（键形如 "$SCRIPTSTRING_QUEST0_5$"、"SCRIPTSTRING_QUEST_TEXT_1"）。
 * 基础 API——不做键格式解释（序号后缀/特殊键归上层 wrapper）；文件缺失抛错（对白文本
 * 缺失不是合法运行态）。语言维度由 provider 的目录回退决定（wz-zh-CN 优先，server
 * config 语言；per-client 语言未来再议）。
 */
export function getScriptStrings(file) {
    let table = scriptStringsCache.get(file);
    if (table == null) {
        const data = getScriptStringProvider().getData("ScriptString/" + file);
        if (data == null) {
            throw new Error("getScriptStrings: ScriptString 文件不存在: " + file);
        }
        table = new Map();
        for (const entry of data.getChildren()) {
            table.set(String(entry.getName()), String(entry.getData()));
        }
        scriptStringsCache.set(file, table);
    }
    return table;
}

/** i18n 消息文本（resources 里的 message code，如 "UseCashItemHandler.handlePacket.message10"） */
export function getMessage(code, ...args) {
    return I18nUtil.getMessage(code, args);
}

/**
 * 本角色的 Character 门面（任务脚本的"player"）。宿主（CharacterScriptRunner）在 context
 * 创建时注入 player actor 全局；本导出是角色实体的派生视图——每次属性访问经 actor 现取
 * character()，与 Java 侧派生视图同纪律（不存槽）。模块装载早于角色接绑（charlist 预览
 * 就会触发道具钩子模块装载），导出本身必须在装载期可求值；能直接从 Character 调的 API
 * （HP/道具/经验/任务/通知）脚本直调，不经过 QuestApi。
 */
export const player = new Proxy({}, {
    get(_target, key) {
        return globalThis.player.character()[key];
    },
});
