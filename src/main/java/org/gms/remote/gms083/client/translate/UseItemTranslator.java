package org.gms.remote.gms083.client.translate;

import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.packets.UseItemPacket;
import org.gms.remote.modules.inventory.client.ConsumeItemEvent;

/**
 * USE_ITEM 翻译：回显事实 1:1 映射到消耗品使用事件（纯映射，无 wire 副作用——
 * 效果应用/公告全归 gameplay）。
 */
public final class UseItemTranslator implements InTranslator<UseItemPacket.UseItem> {

    @Override
    public ClientEvent translate(UseItemPacket.UseItem packet) {
        return new ConsumeItemEvent(packet.slot(), packet.itemId());
    }
}
