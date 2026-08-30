/*
 * 脚本通用 API 门面（ESM）。Java 交互集中在此，道具脚本只面对 JS 惯用 API。
 * 契约与 scripts/item/coupon.js 文件头一致；实现项随背包重构步骤逐个补齐（doc/10）。
 */

// ── 已实现 ──

const Server = Java.type("org.gms.net.server.Server");
const ScriptTimers = Java.type("org.gms.scripting.ScriptTimers");
const TimeZone = Java.type("java.util.TimeZone");
const RateBucketEnum = Java.type("org.gms.client.character.RateBucket");
const WzJsonConverter = Java.type("org.gms.scripting.WzJsonConverter");
const DataProviderFactory = Java.type("org.gms.provider.DataProviderFactory");
const WZFiles = Java.type("org.gms.provider.wz.WZFiles");
const ItemRegistry = Java.type("org.gms.client.inventory.ItemRegistry");

// provider 惰性取用：WZFiles 初始化需要 Spring 容器（wz 语言配置），不在模块装载期触碰
let itemDataProvider = null;

function getItemDataProvider() {
    if (itemDataProvider == null) {
        itemDataProvider = DataProviderFactory.getDataProvider(WZFiles.ITEM);
    }
    return itemDataProvider;
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

/** 道具定义（普通 JS 对象；rate 映射值 = wz 路径）；未登记道具 null */
export function getItemDefinition(itemId) {
    const def = ItemRegistry.of(itemId);
    return def == null ? null : {
        hooks: def.hooks(),
        expRate: def.expRate(),
        mesoRate: def.mesoRate(),
        dropRate: def.dropRate(),
    };
}
