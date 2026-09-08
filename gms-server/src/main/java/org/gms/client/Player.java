package org.gms.client;

import org.gms.client.character.Character;
import org.gms.remote.RemoteClient;

/**
 * 客户端 actor 的对内封装（词汇纪律：player = 客户端 actor，character = {@link Character}
 * 实体——新代码中"角色的"一律说 character，"这个 actor"说 player）。
 *
 * <p><b>获取纪律</b>：Player 只作为环境上下文存在——正在 player strand 上执行的代码经
 * {@link #current()} 零参数取得；strand 之外不可获取（无任何可导航出口：从 Client、从
 * strand 引用钻取均不可达）。跑在其他线程/strand 的代码放别的包，不得使用本类。
 *
 * <p><b>视图语义</b>：character/remote 为派生视图（从 {@link Client} 现取，不存槽）——
 * 商城/换频道转换期间 strand 死亡重建、Character 经 newClient 重绑到新 client，派生视图
 * 零成本自动跟随，无双槽陈旧引用问题。
 *
 * <p>放置规则：org.gms.client.** 的新代码执行期必须满足 {@code Player.current() != null}
 * （入口处 {@link #require} 断言）；{@link #current()} 的语义边界是"本线程正在作为该
 * actor 执行"，不是"本线程在为该 actor 等待"——阻塞在 strand.run 上的等待者拿到 null。
 */
public final class Player {

    private final Client client;
    private final PlayerStrand strand;

    Player(Client client, PlayerStrand strand) {
        this.client = client;
        this.strand = strand;
    }

    /** 当前线程正在作为的 actor；不在 player strand 上执行 → null */
    public static Player current() {
        PlayerStrand s = PlayerStrand.current();
        return s != null ? s.player() : null;
    }

    /** 同 {@link #current()}，但不在 player strand 上时抛出——新代码入口的放置断言（严格契约） */
    public static Player require(String what) {
        Player p = current();
        if (p == null) {
            throw new IllegalStateException("off-strand 访问 [" + what + "]：须在 player strand 上执行，当前线程 " + Thread.currentThread());
        }
        return p;
    }

    /** 本 actor 的执行队列（调度能力可经 Strand 基类跨线程使用；Player 上下文不可） */
    public PlayerStrand strand() {
        return strand;
    }

    /** 传输/会话所有者（netty handler、登录态、hwid 等的原住地） */
    public Client client() {
        return client;
    }


    /** 角色实体（派生视图）；登录前/charlist 阶段为 null */
    public Character character() {
        return client.getPlayer();
    }

    /** 远端客户端门面（派生视图；惰性） */
    public RemoteClient remote() {
        return client.getRemote();
    }

    @Override
    public String toString() {
        return "player[strand=" + strand + "]";
    }
}
