package org.gms.remote.gms083.client.translate;

import org.gms.remote.modules.inventory.client.UseItemEvent;
import org.gms.remote.modules.pet.client.SummonPetEvent;
import org.gms.remote.gms083.client.packets.InventoryPacket.PetFood;
import org.gms.remote.gms083.client.packets.PetPacket.SpawnPet;

/**
 * 宠物族收包翻译：GMS083 事件（byte/short 版本词汇）→ 版本无关语义事件（int 拓宽在此完成）。
 * 纯映射：不读角色数据、不做域决策（no-peek 边界）。按参数类型重载分派。
 */
public final class PetTranslator {

    private PetTranslator() {
    }

    public static SummonPetEvent toEvent(SpawnPet gms) {
        return new SummonPetEvent(gms.slot(), gms.lead());
    }

    public static UseItemEvent toEvent(PetFood gms) {
        return new UseItemEvent(gms.slot(), gms.itemId());
    }
}
