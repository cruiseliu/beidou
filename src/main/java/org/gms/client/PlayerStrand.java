package org.gms.client;

import org.gms.client.character.Character;
import org.gms.infra.ActorMessage;
import org.gms.infra.PipelineContext;
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
 * 生死（doc/12）。actor 诞生点唯一 = 世界域入场（SessionCoordinator.attach）；连接级的
 * 登录/引导 strand 是裸 Strand（跳板域无 actor，Player.current() 为 null）。
 */
public final class PlayerStrand extends Strand {

    private final Player player;
    private final MessageDispatcher dispatcher;

    PlayerStrand(String name) {
        super(name);
        this.player = new Player(this);
        this.dispatcher = new MessageDispatcher(player);
    }

    /**
     * 创建携带 Player 上下文的 strand。<b>唯一合法调用点 = {@code PlayerSession} 构造</b>
     * （世界域入场）；跳板域（登录/引导连接）一律裸 Strand——Player 上下文不越过世界门槛。
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

    @Override
    protected PipelineContext.Owner domain() {
        Character chr = player.character();
        return chr == null ? null : new PipelineContext.Owner(PipelineContext.OwnerType.CHARACTER, chr.getId());
    }

    /** 任务边界推送角色视图（每任务至多一次；详见 CharacterRef.publishView） */
    @Override
    protected void afterTask() {
        player.flushCharacterView();
    }

    /** 类型化消息投递（跨 actor 消息面）：入队后在 player 域内经分发器执行 */
    public void post(ActorMessage msg) {
        post(msg.name(), () -> dispatcher.dispatch(msg));
    }

    /**
     * 会话侧换绑入口（doc/12 权责语义）：对 actor 的写必须走调度器——本方法由
     * PlayerLoggedinHandler 的入场任务在本 strand 上首行调用，把传输附件换绑到新连接。
     */
    public void rebindTo(Client client) {
        player.rebind(client);
    }
}
