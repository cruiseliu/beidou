package org.gms.remote.gms083.client.pipelines;

import org.gms.client.Player;
import org.gms.net.packet.InPacket;
import org.gms.remote.gms083.client.packets.PetPacket;
import org.gms.remote.gms083.client.translate.PetTranslator;
import org.gms.remote.modules.pet.client.SummonPetEvent;
import org.gms.remote.gms083.client.ClientInPipeline;

/**
 * SPAWN_PET 收包管线：召唤/下阵/孵化（gameplay 半边在 CharacterPets.summonPet）。
 * 两步翻译：decode 产出 GMS083 事件（byte/short 版本词汇）→ translate 拓宽为
 * 版本无关语义事件 → 拆装成裸参数调 Handler（事件 record 不出 remote）。
 * 本事件类型需要 unlock 回包（处理完毕解锁客户端）。
 */
public final class PetInPipeline implements ClientInPipeline {

    public String name() {
        return "pet-spawn-in";
    }

    public void handle(InPacket p, Player player) {
        var e = PetTranslator.toEvent(PetPacket.decodeSpawnPet(p));
        player.character().clientEventHandlers().pet().summonPet(e.slot(), e.lead());
        player.remote().basic().unlockActions();
    }
}
