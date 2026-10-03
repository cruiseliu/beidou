package org.gms.remote.gms083.client.packets;

import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.util.AssertUtil;

/**
 * CHANGE_MAP codec（v83）：
 *
 * <ul>
 *   <li>空载荷 = 商城返回；</li>
 *   <li>走门形态（带坐标）：[形态字节][mapid][portalName][落点坐标 x/y(short×2)][skip][wheel][chase]
 *       ——坐标为客户端声明，服务端以自身位置/门定义为准，不消费；</li>
 *   <li>走门形态（无坐标，probe 客户端）：[形态字节][mapid][portalName][skip][wheel][chase]；</li>
 *   <li>转盘复活形态：[形态字节][mapid][portalName=""][skip][wheel][chase]。</li>
 * </ul>
 *
 * 形态判别（结构事实，不用首个形态字节——客户端 00/01 混发）：portalName 空 = 复活；
 * 名非空按名后**精确字节数**二分（3 = 无坐标 / 7 = 带坐标），未知长度与未消费尾字节
 * 一律断言失败（fail loudly——portal-bug2.log：按形态字节判布局曾把带坐标走门错读成
 * 复活，错位后 wheel 读到垃圾，走门被当原地复活静默拒掉）。
 */
public record ChangeMapPacket(boolean fromCashShop, boolean walkForm, int targetMapId,
                              String portalName, boolean wheel,
                              boolean chasing, int chasingX, int chasingY) {

    public static ChangeMapPacket decode(ByteBufReader p) {
        if (p.available() == 0) {
            return new ChangeMapPacket(true, false, 0, "", false, false, 0, 0);
        }
        p.readByte();      // 形态字节（客户端 00/01 混发，不作布局判别——见类注）
        int targetMapId = p.readInt();
        String portalName = p.readString();
        if (portalName.isEmpty()) {
            // 复活形态：[skip][wheel][chase 位]，恰 3 字节
            AssertUtil.isTrue(p.available() == 3,
                    "CHANGE_MAP 复活形态尾长异常（应恰 3 字节）: available=" + p.available());
            p.readByte();                          // skip/保留
            boolean wheel = p.readByte() > 0;
            boolean chasing = readChasing(p);
            assertConsumed(p);
            return new ChangeMapPacket(false, false, targetMapId, portalName, wheel, chasing, 0, 0);
        }
        // 走门形态：名后恰 3 = 无坐标 / 恰 7 = 带坐标（4 坐标 + skip + wheel + chase 位）
        AssertUtil.isTrue(p.available() == 3 || p.available() == 7,
                "CHANGE_MAP 走门形态尾长异常（应恰 3 或 7 字节）: portalName=" + portalName
                        + ", available=" + p.available());
        boolean walkForm = p.available() == 7;
        if (walkForm) {
            p.readInt();   // 落点坐标 x/y（客户端声明；服务端不消费）
        }
        p.readByte();      // skip/保留
        boolean wheel = p.readByte() > 0;
        boolean chasing = readChasing(p);
        assertConsumed(p);
        return new ChangeMapPacket(false, walkForm, targetMapId, portalName, wheel, chasing, 0, 0);
    }

    /** chase 位（=1 时后随恰 8 字节 x/y 载荷；语义侧不支持，translator 断言拦截）。 */
    private static boolean readChasing(ByteBufReader p) {
        boolean flag = p.readByte() == 1;
        if (!flag) {
            return false;
        }
        AssertUtil.isTrue(p.available() == 2 * Integer.BYTES,
                "CHANGE_MAP chase 载荷长度异常（应恰 8 字节）: available=" + p.available());
        return true;
    }

    /** 整包消费断言：任何布局判别/字段读取错位在此响亮失败（本类解码的正确性锚点）。 */
    private static void assertConsumed(ByteBufReader p) {
        AssertUtil.isTrue(p.available() == 0,
                "CHANGE_MAP 有未消费尾字节 " + p.available() + "（布局判别或字段读取错位）");
    }
}
