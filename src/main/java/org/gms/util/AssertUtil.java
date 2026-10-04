package org.gms.util;

/**
 * 硬断言：assert 关键字依赖 -ea 且默认关闭，运行时不变量的守护走本类——
 * 失败即抛 {@link AssertionError}，不依赖 JVM 开关。调用方不捕获，
 * 由既有 fail-safe 层（strand / shim 任务兜底）记日志并继续服务；
 * 服务迁移期 canary 等不变量探测。
 */
public final class AssertUtil {

    private AssertUtil() {
    }

    /** 断言 condition 为 true，否则抛 {@link AssertionError}（message 描述被违反的不变量） */
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void isTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }

    public static AssertionError never(String message) {
        throw new AssertionError(message);
    }

    public static AssertionError never() {
        throw new AssertionError();
    }
}
