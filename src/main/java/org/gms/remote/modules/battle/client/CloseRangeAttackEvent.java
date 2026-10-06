package org.gms.remote.modules.battle.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/** CLOSE_RANGE_ATTACK 语义事件：近战攻击原始申报（应用与广播编排在 battle 域）。 */
public record CloseRangeAttackEvent(CloseRangeAttack attack) implements ClientEvent {

    @Override
    public Module module() {
        return Module.BATTLE;
    }
}
