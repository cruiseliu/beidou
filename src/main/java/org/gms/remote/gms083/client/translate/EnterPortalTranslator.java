package org.gms.remote.gms083.client.translate;

import org.gms.client.Player;
import org.gms.remote.ClientEvent;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.packets.StatChangedPacket;
import org.gms.remote.modules.map.client.EnterPortalEvent;

/**
 * CHANGE_MAP_SPECIAL 翻译：恒等——decode 产物即语义事件本体。
 *
 * <p>afterEmit = 本 opcode 的回包解锁（原 gameplay 各拒绝分支的 unlockActions 散点
 * 收编于此）：无条件直发。warp 成功路径（SET_FIELD 后）与脚本已自带解锁的提示门
 * （ShowHint 语义内联 unlock 双包）因此各多一帧——幂等帧（PET_FOOD 同款裁定）；
 * chalkboard 拒绝分支的解锁缺口亦由此闭合（legacy GenericPortal 的
 * if(!changed) enableActions 兜底在迁移时丢失，此处为权威归位）。
 */
public final class EnterPortalTranslator implements InTranslator<EnterPortalEvent> {

    @Override
    public ClientEvent translate(EnterPortalEvent event) {
        return event;
    }

    @Override
    public void afterEmit(EnterPortalEvent packet, Player player) {
        // 世界端口连接的 remote 实现恒为 Gms083（版本装配不变量，doc/12 跳板/世界分域）
        Gms083 gms = (Gms083) player.remote();
        gms.send(StatChangedPacket.unlock());
    }
}
