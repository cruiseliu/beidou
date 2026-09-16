package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.packets.PetPacket;
import org.gms.remote.modules.pet.client.SummonPetEvent;

/**
 * SPAWN_PET 翻译：byte/short 版本词汇 → int 语义词汇（纯映射）。
 * 本事件类型需要 unlock 回包（处理完毕解锁客户端）——afterEmit 副作用。
 */
public final class PetInTranslator implements InTranslator<PetPacket.SpawnPet> {

    @Override
    public ClientEvent translate(PetPacket.SpawnPet gms) {
        return new SummonPetEvent(gms.slot(), gms.lead());
    }

    @Override
    public void afterEmit(PetPacket.SpawnPet packet, Player player) {
        player.remote().basic().unlockActions();
    }
}
