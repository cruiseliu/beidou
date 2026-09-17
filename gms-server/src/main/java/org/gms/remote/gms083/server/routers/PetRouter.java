package org.gms.remote.gms083.server.routers;

import org.gms.client.character.Character;
import org.gms.client.pet.Pet;
import org.gms.net.packet.Packet;
import org.gms.remote.ServerEventBase;
import org.gms.remote.ServerEventDest;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.modules.pet.server.PetDismissShowEvent;
import org.gms.remote.modules.pet.server.PetFoodResponseEvent;
import org.gms.remote.modules.pet.server.PetIgnoreListEvent;
import org.gms.remote.modules.pet.server.PetNameChangeEvent;
import org.gms.remote.modules.pet.server.PetPanelEvent;
import org.gms.remote.modules.pet.server.PetSummonShowEvent;

/**
 * 宠物域 route：出脸继承自 {@link PetModule}（召唤/下阵/面板/喂食/改名/过滤列表 → 事件在基类），
 * 本类承载 emit/deliver/flush。召唤/下阵/喂食/改名 = 演出交付（广播机制属 map 模块的临时豁免，
 * owner 连接经 legacy client 派生）；面板类事件多对多下沉（PetPanel 骑背包物品体——inventoryT
 * 注入，升级演出随交付即时广播）。
 */
public final class PetRouter extends PetModule implements ServerEventDest {
    private final Gms083 client;

    public PetRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    protected void emit(ServerEventBase event) {
        client.schedule(this, event);
    }

    @Override
    public void deliver(ServerEventBase r) {
        switch (r) {
            case PetSummonShowEvent(Pet pet, int fh) -> onSummon(pet, fh);
            case PetDismissShowEvent(Pet pet, boolean hunger) -> onDismiss(pet, hunger);
            case PetFoodResponseEvent(var slot, var enjoyed, var hasChatBalloon) -> {
                Character chr = client.getLegacyClient().getPlayer();
                chr.getMap().broadcastMessage(client.toLegacyPacket(
                        client.translators().petT.petFoodResponse(chr, slot, enjoyed, hasChatBalloon)));
            }
            case PetNameChangeEvent(var newName, var slot) -> {
                Character chr = client.getLegacyClient().getPlayer();
                chr.getMap().broadcastMessage(chr, client.toLegacyPacket(
                        client.translators().petT.petNameChange(chr, newName, slot)), true);
            }
            case PetPanelEvent snap -> {
                client.translators().inventoryT.onPetPanel(snap);
                if (snap.levelUp()) {
                    // 升级演出：commit 时刻即时广播（先于 flushAll 的 inventory 帧 = legacy 演出→状态时序）。
                    // TODO: rethink about map broadcast——地图广播尚未纳入事务模型（deliver 即发送，
                    //       drop 撤不回；summon/desummon 等入口广播同样在事务保护之外），整体设计待重审。
                    Character chr = client.getLegacyClient().getPlayer();
                    chr.sendPacket(client.toLegacyPacket(client.translators().petT.petLevelUpOwn(snap.petIndex())));
                    chr.getMapRef().broadcastMessage(client.toLegacyPacket(client.translators().petT.petLevelUpForeign(chr, snap.petIndex())));
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

    /** 召唤（出现 + 属性栏刷新）：本体直发 + 他人流广播经 ref 通道（原 repeatToSource=true 的拆分）。 */
    private void onSummon(Pet pet, int fh) {
        Character chr = pet.getOwner();
        Packet spawn = client.toLegacyPacket(client.translators().petT.spawnPet(chr, pet, false, false, fh));
        chr.sendPacket(spawn);
        chr.getMapRef().broadcastMessage(chr.ref(), spawn, false);
        client.send(client.translators().petT.petStatUpdate(chr));
    }

    /** 下阵（消失 + 属性栏刷新；hunger = 因饥饿离场的表现位）。广播口径同召唤。 */
    private void onDismiss(Pet pet, boolean hunger) {
        Character chr = pet.getOwner();
        Packet despawn = client.toLegacyPacket(client.translators().petT.spawnPet(chr, pet, true, hunger, 0));
        chr.sendPacket(despawn);
        chr.getMapRef().broadcastMessage(chr.ref(), despawn, false);
        client.send(client.translators().petT.petStatUpdate(chr));
    }

    @Override
    public void flush() {
    }
}
