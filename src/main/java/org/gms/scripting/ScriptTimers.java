package org.gms.scripting;

import org.graalvm.polyglot.Value;

/**
 * 脚本定时器门面（bind_player.js 的 setTimeout/clearTimeout Java 背书，见 doc/10）。
 * 实际登记/触发归当前脚本宿主（{@link Host}，即各角色的 CharacterScriptRunner）：
 * 到期任务 post 回 owner strand 串行执行；宿主关闭（登出）后静默丢弃。
 *
 * <p>静态方法仅在脚本执行期间可调（执行期间当前线程的宿主已登记，bind 层的嵌套
 * setTimeout 据此归属）；会话外直调抛 IllegalStateException——与旧会话模型契约一致。
 */
public final class ScriptTimers {

    private ScriptTimers() {
    }

    /** 脚本定时器宿主能力（org.gms.client.character.CharacterScriptRunner 实现） */
    public interface Host {
        long setTimeout(Value fn, long delayMs);

        void clearTimeout(long id);
    }

    private static final ThreadLocal<Host> CURRENT = new ThreadLocal<>();

    public static Host currentHost() {
        return CURRENT.get();
    }

    public static void currentHost(Host host) {
        CURRENT.set(host);
    }

    /** 登记定时器：delayMs 后在发起调用的宿主 strand 上执行 fn */
    public static long setTimeout(Value fn, long delayMs) {
        Host host = CURRENT.get();
        if (host == null) {
            throw new IllegalStateException("setTimeout called outside a script session");
        }
        return host.setTimeout(fn, delayMs);
    }

    /** 取消定时器；未知/已触发 id 静默 */
    public static void clearTimeout(long id) {
        Host host = CURRENT.get();
        if (host != null) {
            host.clearTimeout(id);
        }
    }
}
