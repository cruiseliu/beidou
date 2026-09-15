package org.gms.remote.gms083.server.routers;

import org.gms.client.character.Character;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.pet.Pet;
import org.gms.net.packet.Packet;
import org.gms.remote.ServerEventDest;

import java.util.List;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.ServerEventBase;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.modules.pet.server.PetIgnoreListEvent;
import org.gms.remote.modules.pet.server.PetPanelEvent;

/**
 * 宠物域 route：召唤/下阵/喂食/改名为即时 wire 演出（广播机制属 map 模块的临时豁免）；
 * 面板类事件交付（多对多下沉：PetPanel 骑背包物品体——inventoryT 注入，升级演出随交付即时广播）。
 */
public final class PetRouter implements PetModule, ServerEventDest {
    private final Gms083 client;

    public PetRouter(Gms083 client) {
        // this.legacyClient = client;
        this.client = client;
    }

    /** 召唤（出现 + 属性栏刷新）；fh = 召唤点所在 foothold id（地图侧语义，由调用方解析） */
    @Override
    public void summonPet(Pet pet, int fh) {
        Character chr = pet.getOwner();
        Packet spawn = client.toLegacyPacket(client.translators().petT.spawnPet(chr, pet, false, false, fh));
        // 本体直发 + 他人流广播（原 repeatToSource=true 的拆分）：进图编舞（enterMap）在本体
        // strict 窗口内调用本方法，广播扫本体 ref 会触发 canary——自投递走本体直调不经 ref。
        chr.sendPacket(spawn);
        chr.getMap().broadcastMessage(chr, spawn, false);
        client.send(client.translators().petT.petStatUpdate(chr));
    }

    /** 下阵（消失 + 属性栏刷新；hunger = 因饥饿离场的表现位） */
    @Override
    public void dismissPet(Pet pet, boolean hunger) {
        Character chr = pet.getOwner();
        Packet despawn = client.toLegacyPacket(client.translators().petT.spawnPet(chr, pet, true, hunger, 0));
        chr.sendPacket(despawn);   // 同 summonPet：本体直发 + 他人流广播
        chr.getMap().broadcastMessage(chr, despawn, false);
        client.send(client.translators().petT.petStatUpdate(chr));
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

    /** 宠物面板状态推送；levelUp = 本次变更跨越等级边界（附带升级演出）。 */
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
        client.schedule(this, new PetPanelEvent(chr.getId(), chr.getPetIndex(pet),
                (short) host.getPosition(), pet.getPetId(), pet.getItemId(), pet.getName(),
                pet.getLevel(), pet.getTameness(), pet.getFullness(), pet.getFlags(), pet.isAlive(), pet.getExpiration(),
                levelUp));
    }

    /** 拾取过滤列表下发入口：入口即冻结快照；commit 时刻按召唤集逐宠下发（本人）。 */
    @Override
    public void updateIgnoreList(Character chr) {
        client.schedule(this, new PetIgnoreListEvent(chr.getId(), List.copyOf(chr.getExcludedItems())));
    }

    /** 喂食反馈（全图气球） */
    @Override
    public void petFoodResponse(Character chr, int slot, boolean enjoyed, boolean hasChatBalloon) {
        chr.getMap().broadcastMessage(client.toLegacyPacket(client.translators().petT.petFoodResponse(chr, slot, enjoyed, hasChatBalloon)));
    }

    /** 改名演出（全图） */
    @Override
    public void petNameChange(Character chr, String newName, int slot) {
        chr.getMap().broadcastMessage(chr, client.toLegacyPacket(client.translators().petT.petNameChange(chr, newName, slot)), true);
    }

    // ── Router：多对多下沉（面板骑背包物品体 + 升级演出） ──

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case PetPanelEvent snap -> {
                client.translators().inventoryT.onPetPanel(snap);
                if (snap.levelUp()) {
                    // 升级演出：commit 时刻即时广播（先于 flushAll 的 inventory 帧 = legacy 演出→状态时序）。
                    // TODO: rethink about map broadcast——地图广播尚未纳入事务模型（deliver 即发送，
                    //       drop 撤不回；summon/desummon 等入口广播同样在事务保护之外），整体设计待重审。
                    Character chr = client.getLegacyClient().getPlayer();
                    chr.sendPacket(client.toLegacyPacket(client.translators().petT.petLevelUpOwn(snap.petIndex())));
                    chr.getMap().broadcastMessage(client.toLegacyPacket(client.translators().petT.petLevelUpForeign(chr, snap.petIndex())));
                }
            }
            case PetIgnoreListEvent l -> {
                for (Pet pet : client.getLegacyClient().getPlayer().getSummonedPets()) {
                    byte petIndex = client.getLegacyClient().getPlayer().getPetIndex(pet);
                    client.send(client.translators().petT.ignoreList(l.cid(), petIndex, pet.getPetId(), l.itemIds()));
                }
            }
            default -> { }   // 非本模块事件不会到达（owner 标记保证）；防御静默
        }
    }

    @Override
    public void flush() {
    }
}
