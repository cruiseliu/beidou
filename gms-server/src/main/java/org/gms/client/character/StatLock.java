package org.gms.client.character;

import java.util.concurrent.locks.Lock;

/**
 * AutoCloseable 锁——try-with-resources 自动解锁，按声明顺序加锁、逆序解锁。
 * <pre>
 * try (var ignored = StatLock.write(effLock, statWlock).acquire()) {
 *     stats.setHp(hp);
 * }
 * </pre>
 */
public class StatLock implements AutoCloseable {
    private final Lock[] locks;

    private StatLock(Lock... locks) {
        this.locks = locks;
    }

    /** 写锁：effLock 在外、statWlock 在内（服务端标准顺序） */
    public static StatLock write(Lock effLock, Lock statWlock) {
        return new StatLock(effLock, statWlock);
    }

    /** 读锁：只需 statRlock */
    public static StatLock read(Lock statRlock) {
        return new StatLock(statRlock);
    }

    /** 按传入顺序依次加锁，返回 this 供 try-with-resources 使用 */
    public StatLock acquire() {
        for (Lock lock : locks) {
            lock.lock();
        }
        return this;
    }

    /** 逆序解锁 */
    @Override
    public void close() {
        for (int i = locks.length - 1; i >= 0; i--) {
            locks[i].unlock();
        }
    }
}
