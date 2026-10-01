package org.gms.remote.modules.pet;

import org.gms.client.character.Character;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.pet.Pet;
import org.gms.remote.AbstractModule;
import org.gms.remote.modules.pet.server.PetDismissShowEvent;
import org.gms.remote.modules.pet.server.PetFoodResponseEvent;
import org.gms.remote.modules.pet.server.PetIgnoreListEvent;
import org.gms.remote.modules.pet.server.PetNameChangeEvent;
import org.gms.remote.modules.pet.server.PetPanelEvent;
import org.gms.remote.modules.pet.server.PetSummonShowEvent;

import java.util.List;

/**
 * 宠物域（语义基类）：召唤/下阵/成长反馈。事件构造时点即冻结时点（面板标量即时抽取，
 * owner 语义导航由 pet.getOwner() 承担）；召唤/下阵演出携带活 Pet（不完整冻结档，
 * 同 InitializeEvent——翻译在 deliver 时点读取，字段集于召唤时点稳定）。
 */
public abstract class PetModule extends AbstractModule {

    /** 召唤（出现 + 属性栏刷新）；fh = 召唤点所在 foothold id（地图侧语义，由调用方解析） */
    public final void summonPet(Pet pet, int fh) {
        post(new PetSummonShowEvent(pet, fh));
    }

    /** 下阵（消失 + 属性栏刷新；hunger = 因饥饿离场的表现位） */
    public final void dismissPet(Pet pet, boolean hunger) {
        post(new PetDismissShowEvent(pet, hunger));
    }

    /** 宠物到期：物品体转过期态（EXPIRED）。召唤解除由 gameplay 在此之前显式 dismiss。 */
    public final void expire(Pet pet) {
        postPanel(pet, false);
    }

    /** 宠物复活：物品体恢复活态（到期时间等随 Pet 当前状态）。不自动召唤。 */
    public final void revive(Pet pet) {
        postPanel(pet, false);
    }

    /**
     * 宠物面板状态推送（tameness/level/fullness 绝对值，经宠物物品体刷新到达客户端）。
     * levelUp = 本次变更跨越了等级边界（域内 recalcLevel 的计算结果），实现据此决定
     * 是否附带升级演出（commit 时刻广播）；false 不代表面板未变。
     */
    public final void updatePanel(Pet pet, boolean levelUp) {
        postPanel(pet, levelUp);
    }

    /** 喂食反馈（全图气球） */
    public final void petFoodResponse(Character chr, int slot, boolean enjoyed, boolean hasChatBalloon) {
        post(new PetFoodResponseEvent(slot, enjoyed, hasChatBalloon));
    }

    /** 改名演出（全图） */
    public final void petNameChange(Character chr, String newName, int slot) {
        post(new PetNameChangeEvent(newName, slot));
    }

    /**
     * 拾取过滤列表下发（本人）。入口冻结快照（槽位随取随冻：dismiss/召唤的左移会改变槽位）；
     * 遵循入口只记录不发送。
     */
    public final void updateIgnoreList(Character chr) {
        post(new PetIgnoreListEvent(chr.getId(), List.copyOf(chr.getExcludedItems())));
    }

    /** 面板/生命周期共同通路：构造时点冻结（petIndex/宿主槽位经 owner 语义导航抽取）。 */
    private void postPanel(Pet pet, boolean levelUp) {
        Character chr = pet.getOwner();
        if (chr == null) {
            return;
        }
        ItemSlot host = chr.findPetItemSlot(pet.getPetId());
        if (host == null) {
            return;   // 无宿主物品（理论不可达）：面板无从承载
        }
        post(new PetPanelEvent(chr.getId(), chr.getPetIndex(pet),
                (short) host.getPosition(), pet.getPetId(), pet.getItemId(), pet.getName(),
                pet.getLevel(), pet.getTameness(), pet.getFullness(), pet.getFlags(), pet.isAlive(), pet.getExpiration(),
                levelUp));
    }

    public interface Handler {
        void summonPet(int slot, boolean lead);
    }
}
