package org.gms.remote;

import org.gms.client.character.Character;
import org.gms.client.pet.Pet;

/**
 * 语义模块：宠物域（召唤/下阵/成长反馈）。wire 组装归后端私有；
 * 地图广播由实现内决定发送策略（宠物出现/消失对全图可见）。
 */
public interface PetModule {
    /** 召唤（出现 + 属性栏刷新）；fh = 召唤点所在 foothold id（地图侧语义，由调用方解析） */
    void summonPet(Character chr, Pet pet, int fh);

    /** 下阵（消失 + 属性栏刷新；hunger = 因饥饿离场的表现位） */
    void desummonPet(Character chr, Pet pet, boolean hunger);

    /** 宠物属性栏刷新 */
    void petStatUpdate(Character chr);

    /** 升级演出（本人 + 全图） */
    void petLevelUp(Character chr, byte slot);

    /** 喂食反馈（全图气球） */
    void petFoodResponse(Character chr, byte slot, boolean enjoyed, boolean hasChatBalloon);

    /** 改名演出（全图） */
    void petNameChange(Character chr, String newName, byte slot);

    /** 拾取过滤列表下发（本人） */
    void loadExclusionList(Character chr, int petId, byte petIndex, java.util.List<Integer> itemIds);
}
