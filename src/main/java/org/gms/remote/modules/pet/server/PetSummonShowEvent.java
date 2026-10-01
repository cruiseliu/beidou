package org.gms.remote.modules.pet.server;

import org.gms.client.pet.Pet;
import org.gms.remote.ServerEvent;

/**
 * 宠物召唤演出（出现 + 属性栏刷新；fh = 召唤点 foothold）。pet 为活引用——
 * 不完整冻结（同 InitializeEvent 档），翻译在 deliver 时点读取。
 */
public record PetSummonShowEvent(Pet pet, int fh) implements ServerEvent {
}
