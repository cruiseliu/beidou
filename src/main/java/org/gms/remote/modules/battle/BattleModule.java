package org.gms.remote.modules.battle;

import org.gms.remote.AbstractModule;
import org.gms.remote.modules.battle.client.CloseRangeAttack;

/** 战斗域（语义基类）。 */
public abstract class BattleModule extends AbstractModule {

    public interface Handler {
        /**
         * 近战攻击入口（CLOSE_RANGE_ATTACK）：载荷为客户端原始申报——技能等级查表、
         * 伤害上限校验/暴击反转、combo/冷却/dojo 等语义判定全部在本实现内（no-peek）。
         */
        void closeRangeAttack(CloseRangeAttack attack);

        /**
         * 击杀结算入口（MapMonsterKilledMessage 回投）：map actor 已完成团队结算
         * （死亡归属/份额/level split/SHOWDOWN 折算进权重），个人修正与写账在本域。
         */
        void monsterKilled(int mobId, int mobLevel, float expWeight, float partyBonusWeight,
                           boolean white, boolean hasPartySharers, float showdownMult);
    }
}
