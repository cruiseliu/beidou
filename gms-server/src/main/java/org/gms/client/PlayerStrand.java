package org.gms.client;

import org.gms.infra.Strand;

/**
 * player strand：携带 {@link Player} 上下文的具体 strand 类型。
 *
 * <p><b>与 Player 的分工</b>：本类对外只暴露继承来的调度能力（post/run/close，可跨线程导航）
 * 与静态 {@link #current()}；Player 上下文不可从 strand 引用导航获取——actor 上下文的
 * 唯一入口是 {@link Player#current()}（环境获取，仅 strand 任务执行期存在）。
 *
 * <p><b>生命周期</b>：由会话管理器（org.gms.net.server.coordinator.session.PlayerSession）
 * 持有，寿命 = 客户端进程会话（跨换频道/出商城等过渡性重连存活），不再随单条 TCP 连接
 * 生死（doc/12）。连接级的临时引导 strand 也用本类型（Player.client 为 null，attach 后
 * 由 rebind 赋值），保证登录期 handler 的 Player 上下文语义与迁移前一致。
 */
public final class PlayerStrand extends Strand {

    private final Player player;

    PlayerStrand(String name) {
        super(name);
        this.player = new Player(this);
    }

    /**
     * 创建携带 Player 上下文的 strand（会话管理器建会话 strand、Client 建引导 strand）。
     * Player.client 初始为 null，attach 后经 {@link #rebindTo} 赋值。
     */
    public static PlayerStrand create(String name) {
        return new PlayerStrand(name);
    }

    /** 当前线程正在执行的 player strand；不在任何 strand 任务内返回 null */
    public static PlayerStrand current() {
        return Strand.current() instanceof PlayerStrand ps ? ps : null;
    }

    /** 仅同包 {@link Player#current()} 消费；不作为导航出口 */
    Player player() {
        return player;
    }

    /**
     * 会话侧换绑入口（doc/12 权责语义）：对 actor 的写必须走调度器——本方法由
     * PlayerLoggedinHandler 的入场任务在本 strand 上首行调用，把传输附件换绑到新连接。
     */
    public void rebindTo(Client client) {
        player.rebind(client);
    }
}
