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
    }
}
