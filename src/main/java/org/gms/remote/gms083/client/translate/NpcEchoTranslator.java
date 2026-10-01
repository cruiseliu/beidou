package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.client.packets.NPCActionPacket;
import org.gms.remote.gms083.server.packets.NpcActionPacket;
import org.gms.remote.gms083.utils.ByteBufBuilder;

/**
 * NPC_ACTION 翻译：echo 形态的首个实例（任何「服务端只回应、不消费语义」的包都长这样）
 * ——translate 返回 null（零语义事件），回发副作用在 beforeEmit。回发载荷由解码字段落进
 * ByteBufBuilder 重组（talk 全量回放；move 只回 content，尾部 9 字节不回发——现状裁剪；
 * 空包回空）。回发走 S→C 统一发送路径（版本 facade {@link Gms083#send}，含收口日志）；
 * 传输层 loopback 不经语义层（doc/13）。
 */
public final class NpcEchoTranslator implements InTranslator<NPCActionPacket.Action> {

    @Override
    public ClientEvent translate(NPCActionPacket.Action packet) {
        return null;
    }

    @Override
    public void beforeEmit(NPCActionPacket.Action packet, Player player) {
        if (player.character().isChangingMaps()) {
            return;   // 过渡期回声丢弃（防 error 38，现状守卫语义）
        }
        ByteBufBuilder payload = new ByteBufBuilder();
        switch (packet) {
            case NPCActionPacket.Talk(var unknown1, var unknown2, var unknown3) -> {
                payload.writeInt(unknown1);
                payload.writeByte(unknown2);
                payload.writeByte(unknown3);
            }
            case NPCActionPacket.Move move -> payload.writeBytes(move.content());
            case NPCActionPacket.Empty empty -> {
            }
        }
        // 世界端口连接的 remote 实现恒为 Gms083（版本装配不变量，doc/12 跳板/世界分域）
        Gms083 gms = (Gms083) player.remote();
        gms.send(new NpcActionPacket(payload.getBytes()));
    }
}
