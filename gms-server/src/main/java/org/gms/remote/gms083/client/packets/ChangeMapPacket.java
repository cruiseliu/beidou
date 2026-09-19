package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;

/**
 * CHANGE_MAP codec（v83，实测修正后的布局）：
 *
 * <ul>
 *   <li>空载荷 = 商城返回；</li>
 *   <li>mode=01 走门形态：[mapid][portalName][落点坐标 x/y(short×2)][skip][wheel][chase]
 *       ——坐标为客户端声明，服务端以自身位置/门定义为准，不消费；</li>
 *   <li>mode=00 转盘复活形态：[mapid][portalName=""][skip][wheel=01][chase]，无坐标
 *       （比走门形态短 4 字节即此坐标，mapid 两形态皆有）。</li>
 * </ul>
 *
 * legacy 注释「1 = from dying」与本客户端实测相反（普通走门亦发 01）——该位实为
 * 形态标记，此处按 walkForm 读取。GM chase 载荷按 wire 忠实读出（标志 + 剩余恰为
 * 8 字节时的 x/y），语义侧不支持——由 translator 断言拦截。
 */
public record ChangeMapPacket(boolean fromCashShop, boolean walkForm, int targetMapId,
                              String portalName, boolean wheel,
                              boolean chasing, int chasingX, int chasingY) {

    public static ChangeMapPacket decode(ByteBufReader p) {
        if (p.available() == 0) {
            return new ChangeMapPacket(true, false, 0, "", false, false, 0, 0);
        }
        boolean walkForm = p.readByte() == 1;
        int targetMapId = p.readInt();
        String portalName = p.readString();
        if (walkForm) {
            p.readInt();   // 落点坐标 x/y（客户端声明；服务端不消费）
        }
        p.readByte();      // skip/保留
        boolean wheel = p.readByte() > 0;
        boolean chasing = p.readByte() == 1 && p.available() == 2 * Integer.BYTES;
        int chasingX = 0;
        int chasingY = 0;
        if (chasing) {
            chasingX = p.readInt();
            chasingY = p.readInt();
        }
        return new ChangeMapPacket(false, walkForm, targetMapId, portalName, wheel, chasing, chasingX, chasingY);
    }
}
