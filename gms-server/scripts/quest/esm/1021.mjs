// Quest 1021 - Roger's Apple（ESM async 模型，doc/13 §15）
// 瀑布式脚本：await 即对话页推进；"上一步/下一步"链由 InteractionManager 内部管理
// （链内 prev 回退不跨越 await，脚本零状态）；官方流程的链末页都保留"下一步"，
// 一律以 NEXT_PREV 哨兵声明（上一步可回退，末页按下一步整链 resolve）。
// 角色侧操作经 player（Character 门面）；对话/演出经 interact（会话 API）。
// 对白文本来自官方 ScriptString quest0.xml（i18n 视图，索引 = 官方脚本行号），完整官方
// 流程：男号 0 1 4 9 10 / 女号 2 3 4 9 10 / 拒绝 5（next 后收尾）/ 交任务 HP 未满 16、
// 正常 11 12 13+14（官方碎片拼装：13 尾部 CRLF + 脚本侧 QuestIcon 前缀 + 14 + 经验图标
// 字面量）。17 推测为"背包持有苹果"分支——Check.img 数量=0 条件不满足不执行脚本，
// 不可达，不实现；8/15（背包满）理论可触发、官方实测不可行，暂不实现。

import { InteractionManager, NEXT_PREV, i18n } from "../../lib/interaction.js";
import { player } from "../../lib/bind.js";

const q0 = i18n.quest0;

async function start(interact, quest) {
    const [hello, intro] = player.getGender() === 0 ? [0, 1] : [2, 3];
    await interact.sendPages([q0[hello], q0[intro]], NEXT_PREV);
    const accept = await interact.sendAcceptDecline(q0[4]);
    if (!accept) {
        await interact.sendNext(q0[5]);
        return;
    }
    if (player.getHp() >= 50) {
        player.updateHp(25);
    }
    if (!player.haveItem(2010007)) {
        player.gainItem(2010007, 1);
    }
    quest.forceStart();
    await interact.sendPages([q0[9], q0[10]], NEXT_PREV);
    interact.showInfo("UI/tutorial.img/28");
}

async function end(interact, quest) {
    if (player.getHp() < 50) {
        await interact.sendNext(q0[16]);
        return;
    }
    // 奖励展示富文本（官方碎片拼装：QuestIcon 前缀在脚本侧，条目 14 是 "4/0#…" 尾段，
    // 经验尾巴 = 字面量数字 + i18n.EXP；取文本放在调用期——模块装载期不触 WZ provider）
    const reward = "#fUI/UIWindow.img/QuestIcon/" + q0[14]
        + "#fUI/UIWindow.img/QuestIcon/8/0# 10 " + i18n.EXP;
    await interact.sendPages([q0[11], q0[12], q0[13] + reward], NEXT_PREV);
    if (player.canHold(2010000) && player.canHold(2010009)) {
        player.gainExp(10);
        player.gainItem(2010000, 3);
        player.gainItem(2010009, 3);
        quest.forceComplete();
    } else {
        player.dropMessage(1, "Your inventory is full");
    }
}

export const q1021s = new InteractionManager(start).entry;
export const q1021e = new InteractionManager(end).entry;
