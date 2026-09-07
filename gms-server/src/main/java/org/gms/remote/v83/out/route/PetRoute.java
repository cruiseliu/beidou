package org.gms.remote.v83.out.route;

import org.gms.client.Client;
import org.gms.client.character.Character;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.pet.Pet;
import org.gms.net.packet.Packet;
import org.gms.remote.PetModule;
import org.gms.remote.out.events.PetIgnoreListEvent;
import org.gms.remote.out.events.PetPanelEvent;
import org.gms.remote.out.events.SemanticEvent;
import org.gms.remote.v83.out.packet.V83Packet;
import org.gms.remote.v83.out.translate.PetTranslator;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 宠物域 route：面板类事件入口即冻结快照（pet 引用不出本类，发送时机由作用域决定）；
 * 召唤/下阵/喂食/改名对全图可见，为即时 wire 演出（广播机制属 map 模块的临时豁免，
 * 经门面注入的 wire 适配统一 encode + 日志）。
 */
public final class PetRoute implements PetModule {

    private final Client client;
    private final PetTranslator petT;
    private final Consumer<SemanticEvent> dispatch;
    private final Function<V83Packet, Packet> wire;

    public PetRoute(Client client, PetTranslator petT, Consumer<SemanticEvent> dispatch,
                    Function<V83Packet, Packet> wire) {
        this.client = client;
        this.petT = petT;
        this.dispatch = dispatch;
        this.wire = wire;
    }

    /** 召唤（出现 + 属性栏刷新）；fh = 召唤点所在 foothold id（地图侧语义，由调用方解析） */
    @Override
    public void summonPet(Pet pet, int fh) {
        Character chr = pet.getOwner();
        chr.getMap().broadcastMessage(chr, wire.apply(petT.spawnPet(chr, pet, false, false, fh)), true);
        client.sendPacket(wire.apply(petT.petStatUpdate(chr)));
    }

    /** 下阵（消失 + 属性栏刷新；hunger = 因饥饿离场的表现位） */
    @Override
    public void dismissPet(Pet pet, boolean hunger) {
        Character chr = pet.getOwner();
        chr.getMap().broadcastMessage(chr, wire.apply(petT.spawnPet(chr, pet, true, hunger, 0)), true);
        client.sendPacket(wire.apply(petT.petStatUpdate(chr)));
    }

    /** 宠物到期：物品体转过期态（EXPIRED）。召唤解除由 gameplay 在此之前显式 dismiss。 */
    @Override
    public void expire(Pet pet) {
        dispatchPetPanel(pet, false);
    }

    /** 宠物复活：物品体恢复活态（到期时间等随 Pet 当前状态）。不自动召唤。 */
    @Override
    public void revive(Pet pet) {
        dispatchPetPanel(pet, false);
    }

    /**
     * 宠物面板状态推送（tameness/level/fullness 绝对值，经宠物物品体刷新到达客户端）。
     * levelUp = 本次变更跨越了等级边界，实现据此决定是否附带升级演出；false 不代表面板未变。
     */
    @Override
    public void updatePanel(Pet pet, boolean levelUp) {
        dispatchPetPanel(pet, levelUp);
    }

    /** 面板/生命周期共同通路：入口冻结快照并 dispatch（PetPanel），发送时机由作用域决定。 */
    private void dispatchPetPanel(Pet pet, boolean levelUp) {
        Character chr = pet.getOwner();
        if (chr == null) {
            return;
        }
        ItemSlot host = chr.findPetItemSlot(pet.getPetId());
        if (host == null) {
            return;   // 无宿主物品（理论不可达）：面板无从承载
        }
        dispatch.accept(new PetPanelEvent(chr.getId(), chr.getPetIndex(pet),
                (short) host.getPosition(), pet.getPetId(), pet.getItemId(), pet.getName(),
                pet.getLevel(), pet.getTameness(), pet.getFullness(), pet.getFlags(), pet.isAlive(), pet.getExpiration(),
                levelUp));
    }

    /**
     * 拾取过滤列表下发入口：入口即冻结快照（槽位随取随冻——左移会改变槽位）。
     * commit 时刻按召唤集逐宠下发（本人）。
     */
    @Override
    public void updateIgnoreList(Character chr) {
        dispatch.accept(new PetIgnoreListEvent(chr.getId(), List.copyOf(chr.getExcludedItems())));
    }

    /** 喂食反馈（全图气球） */
    @Override
    public void petFoodResponse(Character chr, int slot, boolean enjoyed, boolean hasChatBalloon) {
        chr.getMap().broadcastMessage(wire.apply(petT.petFoodResponse(chr, slot, enjoyed, hasChatBalloon)));
    }

    /** 改名演出（全图） */
    @Override
    public void petNameChange(Character chr, String newName, int slot) {
        chr.getMap().broadcastMessage(chr, wire.apply(petT.petNameChange(chr, newName, slot)), true);
    }
}
