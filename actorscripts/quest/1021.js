import { player, ExpSource } from "../lib/bind_player.js";
import { InteractionManager, NEXT_PREV, i18n } from "../lib/interaction.js";

const MSG = {
    intro1_m: i18n.quest0[0],
    intro2_m: i18n.quest0[1],
    intro1_f: i18n.quest0[2],
    intro2_f: i18n.quest0[3],
    damage: i18n.quest0[4],
    reject: i18n.quest0[5],
    // intro_inv_old: i18n.quest0[6],
    // intro_heal: i18n.quest0[7],
    // inv_full: i18n.quest0[8],
    intro_inv: i18n.quest0[9],
    intro_heal: i18n.quest0[10],
    intro_hotkey: i18n.quest0[11],
    give_reward: i18n.quest0[12],
    bye: i18n.quest0[13],
    rewards: i18n.quest0[14],
    inv_full: i18n.quest0[15],
    not_healed: i18n.quest0[16],
    // not_consumed: i18n.quest0[17],
    exp: i18n.EXP
};

const ROGERS_APPLE = 2010007;
const APPLE = 2010000;
const GREEN_APPLE = 2010009;

async function start(interact, questId, npcId) {
    const intros = player.basic.isMale() ? [MSG.intro1_m, MSG.intro2_m] : [MSG.intro1_f, MSG.intro2_f];
    await interact.sendPages(intros, NEXT_PREV);
    const accept = await interact.sendAcceptDecline(MSG.damage);
    if (!accept) {
        await interact.sendNext(MSG.reject);
        return;
    }
    if (player.stats.getHp() >= 50) {  // TODO: check official server
        player.stats.updateHp(25);
    }
    if (!player.inventory.hasItem(ROGERS_APPLE)) {  // TODO: check official server
        if (!player.inventory.gainItem(ROGERS_APPLE)) {
            interact.sendNext(MSG.inv_full);
            return;
        }
    }
    player.quest.forceStartQuest(questId, npcId);
    await interact.sendPages([MSG.intro_inv, MSG.intro_heal], NEXT_PREV);
    interact.showInfo("UI/tutorial.img/28");
}

async function end(interact, questId, npcId) {
    if (player.stats.getHp() < 50) {
        await interact.sendNext(MSG.not_healed);
        return;
    }
    await interact.sendPages([
        MSG.intro_hotkey,
        MSG.give_reward,
        `${MSG.bye}#fUI/UIWindow.img/QuestIcon/${MSG.rewards}#fUI/UIWindow.img/QuestIcon/8/0# 10 ${MSG.exp}`
    ], NEXT_PREV);
    const success = player.inventory.gainItems([[APPLE, 3], [GREEN_APPLE, 3]]);
    if (success) {
        player.basic.gainExp(10, ExpSource.QUEST);
        player.quest.forceCompleteQuest(questId, npcId);
    } else {
        interact.sendNext(MSG.inv_full);
    }
}

const ROGER = 2000;   // 对话 npc（Roger）：TalkApi 会话无绑定，npc 由脚本提供

export const q1021s = new InteractionManager(start, ROGER).entry;
export const q1021e = new InteractionManager(end, ROGER).entry;
