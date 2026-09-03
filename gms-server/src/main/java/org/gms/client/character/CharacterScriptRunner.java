package org.gms.client.character;

import org.gms.scripting.ScriptTimers;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 角色脚本同步执行域：所有脚本入口语义同步——JS 函数返回后调用方才继续（wrapper
 * 与 JS 之间无中间态窗口）。互斥由脚本会话锁保证，同角色的并发入口串行；任务异常
 * 由会话吞掉（fail-safe），入口正常返回。
 *
 * 脚本内的异步诉求经 setTimeout（bind.js）表达：回调到点后回流本会话串行执行；
 * 会话随登出关闭（{@link #close}），此后提交的任务静默跳过。
 */
public class CharacterScriptRunner {

    private final ScriptTimers.Session session = ScriptTimers.openSession();

    /** 会话内同步执行任务（异常由会话吞掉，本方法正常返回） */
    public void run(Runnable task) {
        session.run(task);
    }

    /**
     * 会话内同步执行任务并取回结果（调用方需要脚本结论时使用，如道具 onUse 钩子）。
     * 任务异常由会话吞掉，返回 null（fail-safe）；会话已关闭时同样返回 null。
     */
    public <T> T call(Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        session.run(() -> result.set(task.get()));
        return result.get();
    }

    /** 关闭会话（登出）；此后提交的任务静默跳过 */
    public void close() {
        session.close();
    }
}
