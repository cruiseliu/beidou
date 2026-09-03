/**
 * 商城宠物食品钩子（data/item/524xxxx_cash_pet_foods.jsonc 全部 0524 商城食品共享此模块）。
 * 替代 UseCashItemHandler 原 524 分支（USE_CASH_ITEM 路径，onUse 钩子框架的第二个消费者）。
 *
 * 喂食参数读 scriptData（consumers/fullness/tameness/alwaysEnjoy，随数据文件演进）。
 * 无匹配宠物时提示并拒绝使用（引擎不消耗）。
 *
 * 返回契约（onUse 框架）：true = 已喂食，引擎统一消耗 1 个；false = 拒绝（脚本已自行反馈）。
 */
import { getMessage, getItemDefinition } from "../lib/bind.js";

const ALL_PETS = -1;

export function onUse(character, item) {
    const pets = character.getPets();

    const scriptData = getItemDefinition(item.getItemId()).scriptData;
    const { consumers, fullness, tameness, alwaysEnjoy } = scriptData;

    for (const pet of pets.getSummonedPets()) {
        if (!consumers.includes(pet.getItemId()) && !consumers.includes(ALL_PETS)) {
            continue;
        }

        const previousFullness = pet.getFullness();
        pet.addFullness(fullness);

        if (alwaysEnjoy || previousFullness <= 75) {
            pet.addTameness(tameness);
        }

        pet.announceFeedResult(alwaysEnjoy || previousFullness < 100);

        pet.saveToDb();
        return true;
    }

    // todo: [refactor] make a better api
    character.dropMessage(1, getMessage("UseCashItemHandler.handlePacket.message10"));
    return false;
}
