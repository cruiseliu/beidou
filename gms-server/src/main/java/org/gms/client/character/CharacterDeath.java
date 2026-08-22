package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.JobEnum;
import org.gms.constants.game.ExpTable;
import org.gms.constants.id.ItemId;
import org.gms.constants.id.MapId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.FieldLimit;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;

/**
 * 死亡/重生模块组件：玩家死亡流程（playerDead）+ 重生（respawn）+ 安全护符状态（usedSafetyCharm）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（respawn/... 对外转发）。
 *
 * 边界：只承载死亡/重生语义——死亡处理（经验损失/护符/异常清理）与复活回城。
 * 怪物重生（respawn）不属本组件；出生点（initialSpawnPoint）留 Character（登录/换图域）；
 * 依赖经 owner 门面调用（getMap/cancelAllBuffs/loseExp/changeMap/...）与组件访问（pq/chair/stats/...）。
 */
class CharacterDeath {
    private final Character owner;

    /** 安全护符是否已消耗（死亡时免经验损失，重生时回 30% HP/MP） */
    private boolean usedSafetyCharm = false;

    CharacterDeath(Character owner) {
        this.owner = owner;
    }

    // ── 死亡 ──

    void playerDead() {    // 包内可见：CharacterStats.hpChangeAction 调用
        if (owner.getMap().isCPQMap()) {
            int losing = owner.getMap().getDeathCP();
            if (owner.pq.getCP() < losing) {
                losing = owner.pq.getCP();
            }
            owner.getMap().broadcastMessage(PacketCreator.playerDiedMessage(owner.getName(), losing, owner.pq.getTeam()));
            owner.pq.gainCP(-losing);
            return;
        }

        owner.cancelAllBuffs(false);
        owner.dispelDebuffs();

        EventInstanceManager eim = owner.getEventInstance();
        if (eim != null) {
            eim.playerKilled(owner);
        }
        int[] charmID = {ItemId.SAFETY_CHARM, ItemId.EASTER_BASKET, ItemId.EASTER_CHARM};
        int possesed = 0;
        int i;
        for (i = 0; i < charmID.length; i++) {
            int quantity = owner.getItemQuantity(charmID[i], false);
            if (quantity > 0) {
                possesed = quantity;
                break;
            }
        }
        usedSafetyCharm = false;
        if (possesed > 0 && !MapId.isDojo(owner.getMapId())) {
            owner.message(I18nUtil.getMessage("Character.useItem.message1"));  //使用安全护符，不扣经验
            InventoryManipulator.removeById(owner.client, ItemConstants.getInventoryType(charmID[i]), charmID[i], 1, true, false);
            usedSafetyCharm = true;
        } else if (owner.getJob() != JobEnum.BEGINNER) { //Hmm...
            if (!FieldLimit.NO_EXP_DECREASE.check(owner.getMap().getFieldLimit())) {  // thanks Conrad for noticing missing FieldLimit check
                int XPdummy = ExpTable.getExpNeededForLevel(owner.getLevel());

                if (owner.getMap().isTown()) {    // thanks MindLove, SIayerMonkey, HaItsNotOver for noting players only lose 1% on town maps
                    XPdummy /= 100;
                } else {
                    if (owner.getLuk() < 50) {    // thanks Taiketo, Quit, Fishanelli for noting player EXP loss are fixed, 50-LUK threshold
                        XPdummy /= 10;
                    } else {
                        XPdummy /= 20;
                    }
                }

                int curExp = owner.getExp();
                if (curExp > XPdummy) {
                    owner.loseExp(XPdummy, false, false);
                } else {
                    owner.loseExp(curExp, false, false);
                }
            }
        }

        if (owner.getBuffedValue(EffectType.MORPH) != null) {
            owner.cancelEffectFromBuffStat(EffectType.MORPH);
        }

        if (owner.getBuffedValue(EffectType.MONSTER_RIDING) != null) {
            owner.cancelEffectFromBuffStat(EffectType.MONSTER_RIDING);
        }

        owner.chair.unsitChairInternal();
        owner.enableActions();
    }

    // ── 重生 ──

    void respawn(int returnMap) {
        respawn(null, returnMap);    // unspecified EIM, don't force EIM unregister in this case
    }

    void respawn(EventInstanceManager eim, int returnMap) {
        if (eim != null) {
            eim.unregisterPlayer(owner);    // some event scripts uses this...
        }
        owner.changeMap(returnMap);

        owner.cancelAllBuffs(false);  // thanks Oblivium91 for finding out players still could revive in area and take damage before returning to town

        if (usedSafetyCharm) {  // thanks kvmba for noticing safety charm not providing 30% HP/MP
            owner.addMPHP((int) Math.ceil(owner.stats.getClientMaxHp() * 0.3), (int) Math.ceil(owner.stats.getClientMaxMp() * 0.3));
        } else {
            owner.updateHp(50);
        }

        owner.setStance(0);
    }
}
