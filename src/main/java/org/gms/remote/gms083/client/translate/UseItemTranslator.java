package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.blocks.UseItemBlock;
import org.gms.remote.modules.inventory.client.UseItemEvent;

/**
 * USE_ITEM / USE_RETURN_SCROLL 共用翻译：回显事实 1:1 映射到使用道具事件（纯映射，
 * 无 wire 副作用）。效果应用/消耗类别分流归 gameplay（脚本 → 特判 → wz 数据优先级）。
 */
public final class UseItemTranslator implements InTranslator<UseItemBlock> {

    @Override
    public ClientEvent translate(UseItemBlock packet) {
        return new UseItemEvent(packet.slot(), packet.itemId());
    }
}
