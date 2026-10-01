package org.gms.util;

import java.util.TimeZone;

/**
 * MapleStory 文件时间戳编码（长整型 FILETIME 风格）。原 PacketCreator.getTime 的
 * 独立提取——被 packet 编码器与业务包共同消费；负数哨兵（-1/-2/-8/-9）为协议内
 * 约定的特殊时间（永久/零点等）。
 */
public final class FieldTime {

    public final static long DEFAULT_TIME = 150842304000000000L;//00 80 05 BB 46 E6 17 02
    /** v83 过期哨兵（= DEFAULT_TIME）：客户端宠物 UI 对 wire ≥ EXPIRED 显示"过期"。 */
    public final static long EXPIRED = DEFAULT_TIME;
    public final static long ZERO_TIME = 94354848000000000L;//00 40 E0 FD 3B 37 4F 01
    /** v83 永久哨兵（2078-12-31T00:00）：客户端宠物 UI 显示"永久"。 */
    public final static long PERMANENT = 150841440000000000L; // 00 C0 9B 90 7D E5 17 02
    private final static long FT_UT_OFFSET = 116444736010800000L + (10000L * TimeZone.getDefault().getOffset(System.currentTimeMillis())); // normalize with timezone offset suggested by Ari

    private FieldTime() {
    }

    public static long getTime(long utcTimestamp) {
        // TODO(debug, 临时): -8 = PERMANENT+12h、-9 = DEFAULT_TIME，配合 @pet debug3/4 到期显示探测，测完移除
        if (utcTimestamp < 0 && utcTimestamp >= -9) {
            if (utcTimestamp == -1) {
                return DEFAULT_TIME;    //high number ll
            } else if (utcTimestamp == -2) {
                return ZERO_TIME;
            } else if (utcTimestamp == -8) {
                return PERMANENT + 432000000000L;   // PERMANENT + 12h（2078-12-31T12:00）
            } else if (utcTimestamp == -9) {
                return DEFAULT_TIME;
            } else {
                return PERMANENT;
            }
        }

        return utcTimestamp * 10000 + FT_UT_OFFSET;
    }
}
