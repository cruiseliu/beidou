package org.gms.remote.gms083.client.pipelines;

import org.gms.client.Player;
import org.gms.net.packet.InPacket;
import org.gms.remote.modules.inventory.client.UseItemEvent;
import org.gms.remote.gms083.client.packets.InventoryPacket;
import org.gms.remote.gms083.client.translate.PetTranslator;
import org.gms.remote.gms083.client.ClientInPipeline;

/**
 * PET_FOOD 收包管线：使用道具意图（喂食域逻辑在道具脚本钩子内，Handler 无宠物逻辑）。
 * 两步翻译同 PetInPipeline。本事件类型需要 unlock 回包（失败路径客户端需解锁；
 * 成功路径喂食反馈帧即确认，多发的 unlock 为幂等帧）。
 */
public final class InventoryInPipeline implements ClientInPipeline {

    public String name() {
        return "inventory-pet-food-in";
    }

    public void handle(InPacket p, Player player) {
        var e = PetTranslator.toEvent(InventoryPacket.decodePetFood(p));
        player.clientEventHandlers().inventory().useItem(e.slot(), e.itemId());
        player.remote().basic().unlockActions();
    }
}
