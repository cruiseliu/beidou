package org.gms.client;

import org.gms.infra.Strand;

/**
 * player strand：携带 {@link Player} 上下文的具体 strand 类型。
 *
 * <p><b>与 Player 的分工</b>：本类对外只暴露继承来的调度能力（post/run/close，可跨线程导航）
 * 与静态 {@link #current()}；Player 上下文不可从 strand 引用导航获取——actor 上下文的
 * 唯一入口是 {@link Player#current()}（环境获取，仅 strand 任务执行期存在）。
 */
public final class PlayerStrand extends Strand {

    private final Player player;

    PlayerStrand(Client client, String name) {
        super(name);
        this.player = new Player(client, this);
    }

    /** 当前线程正在执行的 player strand；不在任何 strand 任务内返回 null */
    public static PlayerStrand current() {
        return Strand.current() instanceof PlayerStrand ps ? ps : null;
    }

    /** 仅同包 {@link Player#current()} 消费；不作为导航出口 */
    Player player() {
        return player;
    }
}
