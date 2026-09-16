package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.remote.modules.map.client.EnterPortalEvent;

/**
 * CHANGE_MAP_SPECIAL codec（v83）：读序即协议序——保留字节 + 传送门名（short 长度前缀
 * + 会话字符集）+ 保留 short。decode 产物即语义事件本体（1:1 映射，无再翻译语义）。
 */
public final class ChangeMapSpecialPacket {

    private ChangeMapSpecialPacket() {
    }

    public static EnterPortalEvent decode(ByteBufReader p) {
        p.readByte();
        String portalName = p.readString();
        p.readShort();
        return new EnterPortalEvent(portalName);
    }
}
