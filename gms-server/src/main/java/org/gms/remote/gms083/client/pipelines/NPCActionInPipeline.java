package org.gms.remote.gms083.client.pipelines;

import org.gms.client.Player;
import org.gms.net.packet.InPacket;
import org.gms.remote.gms083.client.ClientInPipeline;
import org.gms.remote.gms083.client.packets.NPCActionPacket;

/**
 * NPC_ACTION 收包管线：客户端动画回声（传输层 loopback，doc/13）。
 *
 * <p><b>架构特例</b>：本包服务端零语义消费（服务端从不广播、无 gameplay 参与），
 * 因此<b>无模块 Handler</b>——管线在 gms083 内部闭环（回声 codec 封装在
 * {@link NPCActionPacket}，byte 不出类），直接回发客户端。判据：语义模块准入 =
 * 服务端消费语义；纯传输回声（PING 类同）不入模块。
 *
 * <p>唯一的 actor 触点是过渡守卫——queued 化后为 actor 自读（自己的状态自己读）；
 * 过渡期回声由 strand 排序 + channelRead 代际守卫双保险替代旧单薄标志位竞态。
 */
public final class NPCActionInPipeline implements ClientInPipeline {

    public String name() {
        return "npc-action-in";
    }

    public void handle(InPacket p, Player player) {
        if (player.character().isChangingMaps()) {   // 过渡期回声丢弃（防 error 38，现状守卫语义）
            return;
        }
        player.client().sendPacket(NPCActionPacket.echo(p));
    }
}
