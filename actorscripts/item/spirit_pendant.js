/*
 * 精灵吊坠（Pendant of the Spirit，1122017）钩子脚本
 *
 * 语义（原 Java 实现 CharacterEquips.equipPendantOfSpirit 的脚本化）：
 *   - 每穿戴满 1 小时，经验加成提升 10%（1h=10%、2h=20%、3h 起=30%，封顶）；
 *   - 卸下/登出即清零（计时器随脚本状态销毁，计数不做持久化——与旧实现一致）；
 *   - 每次推进给玩家发提示消息（TODO [i18n] 文案暂为字面量，多语言待 JsModule 语言回退支持）。
 *
 * 状态：equippedAt 存穿戴时刻的时间戳，小时数由"现在 - 穿戴时刻"推导（不受节拍漂移影响）。
 * 加成通道：RateBucket.EQUIP 桶（与 ITEM 桶的倍率券相乘叠加、同桶取最大）。
 */
import { RateBucket, getServerLocalTime, setTimeout, clearTimeout } from "../lib/bind.js";

const HOUR_MS = 3600 * 1000;
const MAX_HOURS = 3;

const equippedAt = new Map();   // item → 穿戴时刻（本地墙钟 ms）
const pending = new Map();      // item → 定时器 id

const rateOf = h => 1 + 0.1 * h;
const hoursOf = (equipAt, now) => Math.min(Math.floor((now - equipAt) / HOUR_MS), MAX_HOURS);

function declare(character, item, h) {
    character.getRates().updateExp(RateBucket.EQUIP, item, rateOf(h));
    character.message(h < MAX_HOURS
        ? `精灵吊坠：已装备 ${h} 小时，经验加成 ${h * 10}%`
        : `精灵吊坠：经验加成已达上限 ${h * 10}%`);
}

function scheduleNext(character, item, h) {
    if (h >= MAX_HOURS) {
        return;   // 已封顶：不再排定时器
    }
    const nextAt = equippedAt.get(item) + (h + 1) * HOUR_MS;
    pending.set(item, setTimeout(() => onTick(character, item), Math.max(0, nextAt - now())));
}

function onTick(character, item) {
    pending.delete(item);
    const h = hoursOf(equippedAt.get(item), now());
    declare(character, item, h);
    scheduleNext(character, item, h);
}

const now = () => getServerLocalTime();

export function onEquip(character, item, isLogin) {
    // isLogin=true = 登录装载初始化：穿戴时刻从本次登录重新起算（与旧实现一致）
    const equipAt = now();
    equippedAt.set(item, equipAt);
    character.getRates().updateExp(RateBucket.EQUIP, item, rateOf(0));
    scheduleNext(character, item, 0);
}

export function onUnequip(character, item, isLogout) {
    // isLogout=true = 登出清场（引擎 TODO，契约保留）
    const id = pending.get(item);
    if (id !== undefined) {
        clearTimeout(id);
        pending.delete(item);
    }
    equippedAt.delete(item);
    character.getRates().withdrawExp(RateBucket.EQUIP, item);
}
