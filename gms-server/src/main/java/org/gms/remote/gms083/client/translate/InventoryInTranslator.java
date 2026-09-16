package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.packets.InventoryPacket;
import org.gms.remote.modules.inventory.client.UseItemEvent;

/**
 * PET_FOOD 翻译：byte/short 版本词汇 → int 语义词汇（纯映射）。
 * 本事件类型需要 unlock 回包（失败路径客户端需解锁；成功路径喂食反馈帧即确认，
 * 多发的 unlock 为幂等帧）——afterEmit 副作用。
 */
public final class InventoryInTranslator implements InTranslator<InventoryPacket.PetFood> {

    @Override
    public ClientEvent translate(InventoryPacket.PetFood gms) {
        return new UseItemEvent(gms.slot(), gms.itemId());
    }

    @Override
    public void afterEmit(InventoryPacket.PetFood packet, Player player) {
        player.remote().basic().unlockActions();
    }
}
