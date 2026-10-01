package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.client.packets.InventoryPacket;
import org.gms.remote.gms083.server.packets.StatChangedPacket;
import org.gms.remote.modules.inventory.client.UseItemEvent;

/**
 * PET_FOOD 翻译：byte/short 版本词汇 → int 语义词汇（纯映射）。
 * 本事件类型需要 unlock 回包（失败路径客户端需解锁；成功路径喂食反馈帧即确认，
 * 多发的 unlock 为幂等帧）——afterEmit 副作用，unlock 包直发（版本 send 路径，
 * 不经语义接口：协议应答不走 gameplay 语义）。
 */
public final class InventoryInTranslator implements InTranslator<InventoryPacket.PetFood> {

    @Override
    public ClientEvent translate(InventoryPacket.PetFood gms) {
        return new UseItemEvent(gms.slot(), gms.itemId());
    }

    @Override
    public void afterEmit(InventoryPacket.PetFood packet, Player player) {
        // 世界端口连接的 remote 实现恒为 Gms083（版本装配不变量，doc/12 跳板/世界分域）
        Gms083 gms = (Gms083) player.remote();
        gms.send(StatChangedPacket.unlock());
    }
}
