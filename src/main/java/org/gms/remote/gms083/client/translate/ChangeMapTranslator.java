package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.client.packets.ChangeMapPacket;
import org.gms.remote.gms083.server.packets.StatChangedPacket;
import org.gms.remote.modules.cashshop.client.LeaveCashShopEvent;
import org.gms.remote.modules.map.client.ChangeMapEvent;
import org.gms.remote.modules.map.client.ReviveHereEvent;
import org.gms.util.AssertUtil;

/**
 * CHANGE_MAP 翻译：按 wire 形态路由到三个语义事件（mode 字节 = 客户端声明的形态）。
 * GM chase 载荷语义侧不支持（单机无 GM 裁定，Character.setChasing 无新写入点）——
 * 此处断言拦截，strand/shim fail-safe 记日志后丢包。
 *
 * <p>afterEmit = 本 opcode 的回包解锁（原 gameplay 各拒绝分支的 enableActions/
 * unlockActions 散点收编于此）：无条件直发，warp 成功路径（SET_FIELD 后）与商城返回
 * 路径（ChannelChange 前）因此各多一帧——幂等帧（PET_FOOD 同款裁定）；
 * chalkboard 拒绝分支的解锁缺口亦由此闭合。
 */
public final class ChangeMapTranslator implements InTranslator<ChangeMapPacket> {

    @Override
    public ClientEvent translate(ChangeMapPacket packet) {
        AssertUtil.isTrue(!packet.chasing(),
                "CHANGE_MAP 携带 GM chase 载荷（单机无 GM，不预期出现）: targetMapId=" + packet.targetMapId());
        if (packet.wheel()) {
            // 转盘原地复活意图（真客户端死亡弹窗点击；wheel 位实测可靠，普通走门恒 0）
            return new ReviveHereEvent(packet.wheel(), packet.targetMapId());
        }
        if (packet.fromCashShop()) {
            return new LeaveCashShopEvent();
        }
        return new ChangeMapEvent(packet.targetMapId(), packet.portalName());
    }

    @Override
    public void afterEmit(ChangeMapPacket packet, Player player) {
        // 世界端口连接的 remote 实现恒为 Gms083（版本装配不变量，doc/12 跳板/世界分域）
        Gms083 gms = (Gms083) player.remote();
        gms.send(StatChangedPacket.unlock());
    }
}
