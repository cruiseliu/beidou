package org.gms.remote.modules.cashshop.client;

import org.gms.remote.ClientEvent;
import org.gms.remote.Module;

/**
 * CHANGE_MAP 空载荷语义事件：玩家从商城返回频道的意图。跨频道迁移协议帧（ChannelChange）
 * 属版本实现细节，由 gameplay 侧在域校验通过后发起。
 */
public record LeaveCashShopEvent() implements ClientEvent {

    @Override
    public Module module() {
        return Module.CASHSHOP;
    }
}
