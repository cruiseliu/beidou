package org.gms.remote.modules.pet.server;

import org.gms.client.pet.Pet;
import org.gms.remote.ServerEvent;

/** 宠物下阵演出（消失 + 属性栏刷新；hunger = 因饥饿离场的表现位）。pet 活引用（不完整冻结）。 */
public record PetDismissShowEvent(Pet pet, boolean hunger) implements ServerEvent {
}
