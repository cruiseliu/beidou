package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.packets.CloseRangeAttackPacket;
import org.gms.remote.modules.battle.client.CloseRangeAttack;
import org.gms.remote.modules.battle.client.CloseRangeAttackEvent;

/**
 * CLOSE_RANGE_ATTACK 翻译：原始 wire 载荷 → 语义事件（恒等包装，int 词汇不变换——
 * 伤害改写是 gameplay 知识，翻译层不预读角色/地图）。无 unlock 类回包（失败路径
 * 静默丢弃与历史一致）。
 */
public final class CloseRangeAttackTranslator implements InTranslator<CloseRangeAttack> {

    @Override
    public ClientEvent translate(CloseRangeAttack gms) {
        return new CloseRangeAttackEvent(gms);
    }
}
