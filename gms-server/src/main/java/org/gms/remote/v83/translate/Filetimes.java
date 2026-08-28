package org.gms.remote.v83.translate;

/**
 * 翻译 util：语义时间戳（UTC 秒，-1/-2/-3 哨兵）→ v83 wire 文件时间值（long）。
 * 输入输出均为基础类型——不落地字节（字节写入归 packet 层）。
 */
public final class Filetimes {
    private static final long FT_UT_OFFSET =
            116444736010800000L + (10000L * java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()));

    private Filetimes() {
    }

    public static long toWire(long utcTimestamp) {
        if (utcTimestamp < 0 && utcTimestamp >= -3) {
            return switch ((int) utcTimestamp) {
                case -1 -> 150842304000000000L;   // DEFAULT
                case -2 -> 94354848000000000L;    // ZERO
                default -> 150841440000000000L;   // PERMANENT
            };
        }
        return utcTimestamp * 10000 + FT_UT_OFFSET;
    }
}
