package org.gms.remote;

import java.util.List;

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

    /**
     * 宠物面板状态推送（tameness/level/fullness 绝对值，经宠物物品体刷新到达客户端）。
     * levelUp = 本次变更跨越了等级边界（域内 recalcLevel 的计算结果），实现据此决定
     * 是否附带升级演出（commit 时刻广播）；false 不代表面板未变。
     * pet 引用只在调用返回前有效——实现入口即冻结所需快照。
     */
    void updatePanel(Pet pet, boolean levelUp);

    /** 升级演出（本人 + 全图） */
    void petLevelUp(Character chr, int slot);

    /** 喂食反馈（全图气球） */
    void petFoodResponse(Character chr, int slot, boolean enjoyed, boolean hasChatBalloon);

    /** 改名演出（全图） */
    void petNameChange(Character chr, String newName, int slot);

    /** 拾取过滤列表下发（本人） */
    void loadExclusionList(Character chr, int petId, int petIndex, List<Integer> itemIds);
}
