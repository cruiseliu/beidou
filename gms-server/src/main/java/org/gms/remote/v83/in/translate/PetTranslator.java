package org.gms.remote.v83.in.translate;

import org.gms.remote.in.events.ClientEvent;
import org.gms.remote.in.events.SummonPetEvent;
import org.gms.remote.in.events.UseItemEvent;
import org.gms.remote.v83.in.packet.PetPacket;

import java.util.List;

/**
 * 宠物族收包翻译：解码 record → 语义 ClientEvent（一包可产出多个事件）。
 * 纯映射：不读角色数据、不做域决策（no-peek 边界）。
 */
public final class PetTranslator {

    private PetTranslator() {
    }

    public static List<ClientEvent> toEvents(PetPacket.SpawnPet r) {
        return List.of(new SummonPetEvent(r.slot(), r.lead()));
    }

    public static List<ClientEvent> toEvents(PetPacket.PetFood r) {
        return List.of(new UseItemEvent(r.slot(), r.itemId()));
    }
}
