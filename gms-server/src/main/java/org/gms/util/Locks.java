package org.gms.util;

import java.util.concurrent.locks.Lock;

/**
 * AutoCloseable 多锁工具——按传入顺序加锁，close 时逆序解锁，配合 try-with-resources 使用。
 * <pre>
 * try (var ignored = Locks.acquire(buffs.lock, stats.wLock)) {
 *     ...
 * }
 * </pre>
 * 多锁场景必须遵守全局加锁顺序（如 buffs.lock → stats.wLock）以防死锁。
 */
public final class Locks implements AutoCloseable {
    private final Lock[] locks;

    private Locks(Lock... locks) {
        this.locks = locks;
    }

    /** 按传入顺序依次加锁，返回 AutoCloseable 供 try-with-resources 使用 */
    public static Locks acquire(Lock... locks) {
        for (Lock lock : locks) {
            lock.lock();
        }
        return new Locks(locks);
    }

    /** 逆序解锁 */
    @Override
    public void close() {
        for (int i = locks.length - 1; i >= 0; i--) {
            locks[i].unlock();
        }
    }
}
