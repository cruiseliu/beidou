package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.client.packets.NPCActionPacket;

/**
 * NPC_ACTION 翻译：echo 形态的首个实例（任何「服务端只回应、不消费语义」的包都长这样）
 * ——translate 返回 null（零语义事件），回发副作用在 beforeEmit。传输层 loopback 不经
 * 语义层（doc/13）；wire 信封三分支已在 codec 内解析，回发按解析结果原样复制。
 */
public final class NpcEchoTranslator implements InTranslator<NPCActionPacket.Echo> {

    @Override
    public ClientEvent translate(NPCActionPacket.Echo packet) {
        return null;
    }

    @Override
    public void beforeEmit(NPCActionPacket.Echo packet, Player player) {
        if (player.character().isChangingMaps()) {
            return;   // 过渡期回声丢弃（防 error 38，现状守卫语义）
        }
        player.client().sendPacket(NPCActionPacket.echo(packet));
    }
}
