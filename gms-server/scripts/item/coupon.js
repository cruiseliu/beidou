/*
 * 倍率券钩子脚本（0521 exp 券 / 0536 drop 券）
 *
 * 真相源（Item.wz）：
 *   - info/rate          倍率真值（字段名由 ItemDefinition 的 expRate/mesoRate/dropRate 映射声明）
 *   - spec/expR / drpR   客户端图标档位（1..4 / 1..3），不是倍率，勿用作倍率
 *   - info/time          生效窗口表，"DDD:HH-HH"（含头不含尾），如 "WED:18-20"；同日窗不跨日
 *
 * 行为：在背包中时按当前时刻向角色 ITEM 桶声明/撤销倍率（recalc 统一刷新数值与 buff 图标）；
 *       定时器链在每个窗口边界（本窗口结束 / 下一窗口开始）自动重新评估。
 *
 * 引擎契约（按浏览器/node 习惯书写）：
 *   - 模块上下文常驻：全局变量（含 pendingTimers）在导出函数与定时器回调间共享；
 *     有未触发定时器时上下文不会释放。
 *   - 无并行：模块内所有执行（hooks 与定时器回调）串行，脚本按单线程书写。
 *   - 登出时引擎对每个在包道具回调 onLeaveInventory(chr, item, isLogout=true)（引擎侧 TODO，
 *     脚本按"回调必然触发"书写——定时器与状态因此总能被清干净）。
 *
 * import 的 API 契约（bind.js，本注释即约定）：
 *   getItemDefinition(itemId) -> { hooks, expRate, mesoRate, dropRate } | null
 *       普通 JS 对象（record 已展开）；未声明的字段为 null。
 *       rate 映射值 = wz 路径（如 "info/rate"），指向该 kind 倍率真值所在节点
 *   getWzItemData(itemId)     -> 整棵 wz 道具节点（无语义转换：imgdir → 对象、
 *       标量原样、画布/向量省略），如 { info: { rate: 2, time: {"0": "MON:18-20", ...} },
 *       spec: { expR: 2 } }；无该物品返回 null
 *   getServerLocalTime()      -> int  // 服务器本地墙钟时间戳（本时区 1970-01-01 00:00 起的 ms）
 *   setTimeout(fn, delayMs)   -> id  // delayMs 后执行 fn（模块上下文内串行）
 *   clearTimeout(id)                  // 对未知/已触发 id 静默
 *   RateBucket                -> Java enum（RateBucket.ITEM）
 *   character.getRates()      -> { updateExp(bucket,item,n), updateMeso(...), updateDrop(...),
 *                                  withdrawExp(bucket,item), withdrawMeso(...), withdrawDrop(...),
 *                                  recalc() }
 *
 * 时间约定：所有窗口/边界计算使用同一时刻快照（now，本地时间戳整数），每次回调只取一次、
 * 层层传参——不考虑计算中途跨界的可能。周坐标 = 本地时间戳平移到周日起点后对一周取模。
 */

import { getItemDefinition, getWzItemData, getServerLocalTime, setTimeout,
         clearTimeout, RateBucket } from "../lib/bind.js";

const DAY_MS = 24 * 60 * 60 * 1000;
const WEEK_MS = 7 * DAY_MS;
// 本地 1970-01-01 是周四：时间戳直接对一周取模的起点是周四，平移 4 天把周起点修正到周日
const WEEK_PHASE = 4 * DAY_MS;
const DAY_INDEX = { SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6 };

/** 声明中物品的待触发定时器 id。键 = item 对象；登出回调保证在包道具必然走一次 leave，必清。 */
const pendingTimers = new Map();

// ── 纯计算：窗口与倍率（now = 本地时间戳，由调用方取一次传入） ──

/** "WED:18-20" → { startMs, endMs }，均为周坐标（周日起点，含头不含尾） */
function parseWindow(entry) {
    const sep = entry.indexOf(":");
    const dash = entry.indexOf("-", sep);
    const day = DAY_INDEX[entry.substring(0, sep)] * DAY_MS;
    return {
        startMs: day + Number(entry.substring(sep + 1, dash)) * 3600 * 1000,
        endMs: day + Number(entry.substring(dash + 1)) * 3600 * 1000,
    };
}

/** 本地时间戳 → 周坐标毫秒 */
function weekMs(now) {
    return (now + WEEK_PHASE) % WEEK_MS;
}

/** 按 wz 路径（"info/rate"）在节点树中取值；任一段缺失返回 undefined */
function readPath(data, path) {
    let node = data;
    for (const seg of path.split("/")) {
        node = node == null ? undefined : node[seg];
    }
    return node;
}

/** 生效窗口表（info/time：键名序号 → "DDD:HH-HH"）；无 time 表 = 恒常有效（返回 null，免定时器） */
function windowsOf(item) {
    const data = getWzItemData(item.getItemId());
    const table = data != null ? readPath(data, "info/time") : undefined;
    if (table == null || typeof table !== "object") {
        return null;
    }
    // 键名 = 窗口序号；数字键按数值序，其余按字典序
    const keys = Object.keys(table).sort((a, b) => {
        const na = Number(a), nb = Number(b);
        return Number.isNaN(na) || Number.isNaN(nb) ? (a < b ? -1 : 1) : na - nb;
    });
    if (keys.length === 0) {
        return null;
    }
    return keys.map(k => parseWindow(table[k]));
}

function inWindow(windows, now) {
    const w = weekMs(now);
    return windows.some(win => win.startMs <= w && w < win.endMs);
}

/** 距下一窗口边界的毫秒数：在窗口内 → 本窗口结束；否则 → 下一窗口开始；恒常有效 → -1 */
function msToNextBoundary(windows, now) {
    const w = weekMs(now);
    let best = Infinity;
    for (const win of windows) {
        if (win.startMs <= w && w < win.endMs) {
            best = Math.min(best, win.endMs - w);            // 在本窗口内：等它结束
        } else {
            let d = (win.startMs - w) % WEEK_MS;             // 否则：等它的下一次开始
            if (d <= 0) d += WEEK_MS;
            best = Math.min(best, d);
        }
    }
    return best === Infinity ? -1 : best;
}

/** now 时刻的倍率声明；不生效（未登记/无 wz 数据/无倍率值/窗外）返回 null */
function bonusOf(item, now) {
    const def = getItemDefinition(item.getItemId());
    const data = getWzItemData(item.getItemId());
    if (def == null || data == null) {
        return null;
    }
    const windows = windowsOf(item);
    if (windows != null && !inWindow(windows, now)) {
        return null;
    }
    // 倍率值 = 定义映射声明的 wz 路径取值；未映射/路径缺失 = undefined（声明侧 !(x>0) 才生效）
    const exp = def.expRate ? readPath(data, def.expRate) : 0;
    const meso = def.mesoRate ? readPath(data, def.mesoRate) : 0;
    const drop = def.dropRate ? readPath(data, def.dropRate) : 0;
    if (!(exp > 0) && !(meso > 0) && !(drop > 0)) {
        return null;
    }
    return { exp: exp, meso: meso, drop: drop, windows: windows };
}

// ── 声明与自续评估链 ──

/** 按 now 时刻重新声明：先按 kind 对称全撤（绝对值替换语义）再按需声明，recalc 统一刷新 */
function declare(character, item, now) {
    const rates = character.getRates();
    rates.withdrawExp(RateBucket.ITEM, item);
    rates.withdrawMeso(RateBucket.ITEM, item);
    rates.withdrawDrop(RateBucket.ITEM, item);

    const b = bonusOf(item, now);
    if (b != null) {
        if (b.exp > 0) rates.updateExp(RateBucket.ITEM, item, b.exp);
        if (b.meso > 0) rates.updateMeso(RateBucket.ITEM, item, b.meso);
        if (b.drop > 0) rates.updateDrop(RateBucket.ITEM, item, b.drop);
    }
    rates.recalc();
    return b;
}

/** 声明 + 排下一边界定时器（窗口内外两个方向共用同一条链） */
function refresh(character, item, now) {
    const b = declare(character, item, now);
    const windows = b != null ? b.windows : windowsOf(item);
    const ms = windows == null ? -1 : msToNextBoundary(windows, now);
    if (ms >= 0) {
        pendingTimers.set(item, setTimeout(() => refresh(character, item, getServerLocalTime()), ms));
    }
}

// ── hooks ──

export function onEnterInventory(character, item, isLogin) {
    // isLogin：角色上线重初始化 vs 游戏过程加入；对声明逻辑无差别，参数按契约保留
    refresh(character, item, getServerLocalTime());
}

export function onLeaveInventory(character, item, isLogout) {
    // isLogout=true：登出清场（引擎 TODO，见文件头契约）；false：游戏过程移除
    const id = pendingTimers.get(item);
    if (id !== undefined) {
        clearTimeout(id);
        pendingTimers.delete(item);
    }
    const rates = character.getRates();
    rates.withdrawExp(RateBucket.ITEM, item);
    rates.withdrawMeso(RateBucket.ITEM, item);
    rates.withdrawDrop(RateBucket.ITEM, item);
    rates.recalc();
}
