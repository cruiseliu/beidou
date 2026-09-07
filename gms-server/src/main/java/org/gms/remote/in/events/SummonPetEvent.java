package org.gms.remote.in.events;

/** SPAWN_PET 解码结果：召唤/下阵意图（slot = CASH 背包槽位，lead = 是否作为头宠）。 */
public record SummonPetEvent(short slot, boolean lead) implements ClientEvent {
}
