package org.gms.remote.v83.out.translate;

/**
 * 翻译 util：语义时间戳（UTC 秒，-1/-2/-3 哨兵）→ v83 wire 文件时间值（long）。
 * 输入输出均为基础类型——不落地字节（字节写入归 packet 层）。
 */
public final class Filetimes {
    /** v83 永久哨兵（2078-12-31T00:00）：客户端宠物 UI 对其显示"永久"。 */
    public static final long PERMANENT = 150841440000000000L;
    /** v83 过期哨兵（2079-01-01T00:00）：客户端宠物 UI 对 wire ≥ 此值显示"过期"——失活宠物的标记值。 */
    public static final long EXPIRED = 150842304000000000L;

    private static final long FT_UT_OFFSET =
            116444736010800000L + (10000L * java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()));

    private Filetimes() {
    }

    public static long toWire(long utcTimestamp) {
        // TODO(debug, 临时): -8 = PERMANENT+12h、-9 = EXPIRED，配合 @pet debug3/4 到期显示探测，测完移除
        if (utcTimestamp < 0 && utcTimestamp >= -9) {
            if (utcTimestamp == -8) {
                return PERMANENT + 432000000000L;   // PERMANENT + 12h（2078-12-31T12:00）
            }
            if (utcTimestamp == -9) {
                return EXPIRED;
            }
            return switch ((int) utcTimestamp) {
                case -1 -> EXPIRED;                 // DEFAULT（通用物品"无期限"哨兵，值同 EXPIRED）
                case -2 -> 94354848000000000L;      // ZERO
                default -> PERMANENT;
            };
        }
        return utcTimestamp * 10000 + FT_UT_OFFSET;
    }
}
