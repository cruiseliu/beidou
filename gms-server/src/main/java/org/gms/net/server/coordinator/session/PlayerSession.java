package org.gms.net.server.coordinator.session;

import org.gms.client.Client;
import org.gms.client.Player;
import org.gms.client.PlayerStrand;
import org.gms.infra.DeadlineTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.gms.client.character.Character;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.remote.RemoteClient;
import org.gms.util.PacketCreator;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 客户端进程会话：actor 管理器对一个 actor（{@link PlayerStrand} + Player）的封装（doc/12）。
 * 寿命 = 客户端进程的游戏会话（账号 attach → 最终登出），跨换频道/出商城/跨世界等
 * 过渡性重连存活；TCP 连接（{@link Client}）是可换绑的传输附件。
 *
 * <p><b>权责语义（doc/12 §3.3）</b>：本类是管理器自有状态（state / client 引用槽 / done）
 * 的唯一写者，可在任意线程操作；对 actor（Player/Character）的一切写必须以任务形式
 * 投递到 {@link #strand} 执行（attach 方的 rebind、finalize 的登出收尾体），不允许
 * off-strand 写 actor。跨线程读仅限过渡不变字段。
 *
 * <p><b>状态机</b>：ATTACHED →（markTransition）TRANSITION →（adoptClient）ATTACHED；
 * ATTACHED/TRANSITION →（detach/finalize/reaper）CLOSING → CLOSED。
 * 状态翻转在本对象锁下进行；strand 串行的是"对 Character 的影响"，不是状态翻转
 * ——finalize 先翻 CLOSING 再 close strand，"已 CLOSING" happens-before "不再收活"。
 */
public final class PlayerSession {
    private static final Logger log = LoggerFactory.getLogger(PlayerSession.class);

    /** 过渡死期：与 getLoginState 的 accounts.loggedin 过渡超时对齐（内存态与 DB 态同寿命） */
    private static final long TRANSITION_DEADLINE_MS = 30_000;
    /** 等待旧会话终结的上限（顶号/重入 attach 路径） */
    private static final long AWAY_CLOSED_TIMEOUT_MS = 15_000;

    public enum State { ATTACHED, TRANSITION, CLOSING, CLOSED }

    private final int accountId;
    private final PlayerStrand strand;
    private final Object lock = new Object();

    private State state = State.ATTACHED;
    private volatile Client client;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private volatile DeadlineTimer.TimerHandle deadline;

    PlayerSession(int accountId) {
        this.accountId = accountId;
        this.strand = PlayerStrand.create("acct-" + accountId);
    }

    public int accountId() {
        return accountId;
    }

    /** 本会话的 actor 执行队列（寿命 = 本会话，跨连接存活） */
    public PlayerStrand strand() {
        return strand;
    }

    /** 当前传输附件；过渡窗口/终结后为 null */
    public Client client() {
        return client;
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** 终态信号：finalizer 完成时 complete（顶号/重入 attach 等待旧会话收尾用） */
    public CompletableFuture<Void> done() {
        return done;
    }

    /** fresh 路径：registry 新建后绑定首个传输（由 SessionCoordinator 调用） */
    void attachClient(Client c) {
        synchronized (lock) {
            this.state = State.ATTACHED;
            this.client = c;
        }
    }

    /**
     * adopt 路径（过渡重连）：TRANSITION → ATTACHED 并绑新传输。
     * 状态竞争（reaper 抢先终结）返回 false，调用方重查 registry。
     */
    boolean adoptClient(Client c) {
        synchronized (lock) {
            if (state != State.TRANSITION) {
                return false;
            }
            state = State.ATTACHED;
            client = c;
            cancelDeadline();
            return true;
        }
    }

    /** 进入过渡（换频道/出商城/跨世界）；仅 ATTACHED 有效 */
    public void markTransition() {
        synchronized (lock) {
            if (state == State.ATTACHED) {
                state = State.TRANSITION;
            }
        }
    }

    /**
     * 连接死亡（netty channelInactive 路径；调用线程为 event loop）。带入参做代际校验：
     * ATTACHED → 真登出（{@code logoutBody} 作为收尾体在会话 strand 上执行）；
     * TRANSITION → 仅解绑 + 武装过渡死期；其余/非当前传输 → 忽略。
     */
    public void detach(Client c, Runnable logoutBody) {
        synchronized (lock) {
            if (client != c) {
                return;   // 代际：已换绑/已解绑/已终结
            }
            if (state == State.TRANSITION) {
                client = null;
                armDeadline(c);
                return;
            }
            if (state != State.ATTACHED) {
                return;   // CLOSING/CLOSED
            }
            client = null;
        }
        finalize("logout", logoutBody);
    }

    /**
     * 立即终结（顶号/封禁/关服）；幂等。{@code logoutBody} 在会话 strand 上执行
     * （END 哨兵排空后），终态时 {@link #done} complete。
     */
    public void finalize(String reason, Runnable logoutBody) {
        synchronized (lock) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return;
            }
            state = State.CLOSING;
            client = null;
        }
        cancelDeadline();
        SessionCoordinator.getInstance().removeSession(this);
        log.info("会话 acct-{} 终结（{}），收尾在会话 strand 上执行", accountId, reason);
        strand.finalizeWith(() -> {
            try {
                logoutBody.run();
            } finally {
                synchronized (lock) {
                    state = State.CLOSED;
                }
                done.complete(null);
            }
        });
        strand.close();
    }

    /** 等待会话到达终态（顶号/重入 attach 路径；超时不抛，交由调用方重查 registry） */
    public void awaitClosed() {
        try {
            done.get(AWAY_CLOSED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("等待会话 acct-{} 终结超时（{}ms）", accountId, AWAY_CLOSED_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("等待会话 acct-{} 终结异常", accountId, e);
        }
    }

    /**
     * 过渡死期 reaper：挂在会话 strand 上（strand 存活才存在过渡死期问题；adopt 后
     * 空转）。到点仍 TRANSITION 才按真登出收尾——会话闭环（strand 终结/registry 摘除/
     * 角色保存）；Character 的 storage 留守与 DB loggedin 自愈对齐现状。
     */
    private void armDeadline(Client c) {
        cancelDeadline();
        deadline = DeadlineTimer.schedule(strand, DeadlineTimer.now() + TRANSITION_DEADLINE_MS,
                "session-transition-deadline", t -> {
                    synchronized (lock) {
                        if (state != State.TRANSITION) {
                            return;   // 已被 adopt / 已终结
                        }
                        state = State.CLOSING;
                        client = null;
                    }
                    SessionCoordinator.getInstance().removeSession(this);
                    log.info("会话 acct-{} 过渡死期到（{}ms 无重入），按登出收尾", accountId, TRANSITION_DEADLINE_MS);
                    strand.finalizeWith(() -> {
                        try {
                            c.logoutFinalizer(false, false);
                        } finally {
                            synchronized (lock) {
                                state = State.CLOSED;
                            }
                            done.complete(null);
                        }
                    });
                    strand.close();
                });
    }

    private void cancelDeadline() {
        DeadlineTimer.TimerHandle d = deadline;
        if (d != null) {
            deadline = null;
            d.cancel();
        }
    }

    @Override
    public String toString() {
        return "session[acct-" + accountId + " " + state() + "]";
    }

    // ── 世界入口（PLAYER_LOGGEDIN，doc/12 §21）：语义层初始化协议 ──
    // 分发：Gms083.resolveHandler 对 PLAYER_LOGGEDIN 特判（queued 裸 strand）→
    // RemoteClientBase.clientInit(characterId, legacyClient) → 本方法。handler 形态退役。

    /** 账号级入场互斥（防双重 loggedin 并发错乱登录态；原 handler 静态簿记） */
    private static final Set<Integer> ATTEMPTING_ACCOUNTS = new HashSet<>();


    /**
     * 绑定传输到角色会话（"创建 strand 或者换绑"，doc/12 §21）：PLAYER_LOGGEDIN 的
     * 全部语义——协议前半（角色获取/票据/登录态翻转，裸 strand 上，半加载态语义）+
     * 会话建立（attach：fresh 诞生/adopt 换绑/顶号终结）+ 入场编舞 post 会话 strand。
     * 原 PlayerLoggedinHandler verbatim 迁移；体中 c 即 legacyClient。
     *
     * @param remote       语义层实例（分发壳捕获，未经 Client 导航）
     * @param legacyClient 传输附件（分发壳 handlePacket 参数直传；RemoteClient 不暴露）
     */
    private static boolean tryAcquireAccount(int accId) {
        synchronized (ATTEMPTING_ACCOUNTS) {
            if (ATTEMPTING_ACCOUNTS.contains(accId)) {
                return false;
            }
            ATTEMPTING_ACCOUNTS.add(accId);
            return true;
        }
    }

    private static void releaseAccount(int accId) {
        synchronized (ATTEMPTING_ACCOUNTS) {
            ATTEMPTING_ACCOUNTS.remove(accId);
        }
    }

    public static void bindClient(int characterId, RemoteClient remote, Client legacyClient) {
        final Client c = legacyClient;
        final Server server = Server.getInstance();


        if (!c.tryacquireClient()) {
            // thanks MedicOP for assisting on concurrency protection here
            c.sendPacket(PacketCreator.getAfterLoginError(10));
        }

        try {
            World wserv = server.getWorld(c.getWorld());
            if (wserv == null) {
                c.disconnect(true, false);
                return;
            }

            Channel cserv = wserv.getChannel(c.getChannel());
            if (cserv == null) {
                c.setChannel(1);
                cserv = wserv.getChannel(c.getChannel());

                if (cserv == null) {
                    c.disconnect(true, false);
                    return;
                }
            }

            Character player = wserv.getPlayerStorage().getCharacterById(characterId);

            boolean newcomer = false;
            if (player == null) {
                try {
                    player = Character.loadCharFromDB(characterId, c, true);
                    newcomer = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (player == null) { //If you are still getting null here then please just uninstall the game >.>, we dont need you fucking with the logs
                    c.disconnect(true, false);
                    return;
                }
            }

            if (!server.validateCharacteridInTransition(c, characterId)) {
                c.disconnect(true, false);
                return;
            }

            c.setAccID(player.getAccountId());

            final Hwid hwid;
            if (newcomer) {
                // 按账号拾取登录会话 hwid（原按远程 IP 取出即删，同 IP 并发登录会互相挤掉，见 doc/TODO.md）
                hwid = SessionCoordinator.getInstance().pickLoginSessionHwid(player.getAccountId());
                if (hwid == null) {
                    c.disconnect(true, false);
                    return;
                }
            } else {
                // 过渡重入：旧连接可能已死（悬挂引用）——优先从会话协调器的 hwid 缓存拾取
                // （CharSelected 时登记），缓存未命中再回退悬挂旧 Client（过渡分支不清理 hwid，仍可读）
                Hwid cached = SessionCoordinator.getInstance().getGameSessionHwid(player.getAccountId());
                hwid = cached != null ? cached : player.getClient().getHwid();
            }

            c.setHwid(hwid);

            boolean allowLogin = true;

                /*  is this check really necessary?
                if (state == Client.LOGIN_SERVER_TRANSITION || state == Client.LOGIN_NOTLOGGEDIN) {
                    List<String> charNames = c.loadCharacterNames(c.getWorld());
                    if(!newcomer) {
                        charNames.remove(player.getName());
                    }

                    for (String charName : charNames) {
                        if(wserv.getPlayerStorage().getCharacterByName(charName) != null) {
                            allowLogin = false;
                            break;
                        }
                    }
                }
                */

            int accId = c.getAccID();
            if (tryAcquireAccount(accId)) { // Sync this to prevent wrong login state for double loggedin handling
                try {
                    int state = c.getLoginState();
                    if (state != Client.LOGIN_SERVER_TRANSITION || !allowLogin) {
                        c.setAccID(0);

                        if (state == Client.LOGIN_LOGGEDIN) {
                            c.disconnect(true, false);
                        } else {
                            c.sendPacket(PacketCreator.getAfterLoginError(7));
                        }

                        return;
                    }
                    c.updateLoginState(Client.LOGIN_LOGGEDIN);
                } finally {
                    releaseAccount(accId);
                }
            } else {
                c.setAccID(0);
                c.sendPacket(PacketCreator.getAfterLoginError(10));
                return;
            }

            if (!newcomer) {
                // 过渡重入：读悬挂旧 Client 的账号级不可变设置（language/slots，过渡分支不清理）
                c.setLanguage(player.getClient().getLanguage());
                c.setCharacterSlots((byte) player.getClient().getCharacterSlots());
            }

            // —— 会话建立（doc/12 §3.4）：attach 决定 fresh/adopt/顶号等待；入场流程（含
            // rebind）作为单个任务投递到会话 strand，FIFO 排在该会话既有任务之后——旧连接
            // 收尾与新连接进入在此获得全序。newClient 依赖会话 strand（strandSlot 安装），
            // 故一并移入入场任务。
            PlayerSession session = SessionCoordinator.getInstance().attach(c, accId);
            c.attachTo(session);
            final Character entered = player;
            final boolean firstEntry = newcomer;
            session.strand().post("loggedin-enter", () -> {
                if (!c.tryacquireClient()) {   // 与前半段的并发保护对齐（MedicOP）
                    c.sendPacket(PacketCreator.getAfterLoginError(10));
                    return;
                }
                try {
                    session.strand().rebindTo(c);   // 跨 actor 写：跑在 actor 上（doc/12 权责语义）
                    // Character 从属 Player（doc/12 §21）：槽位在 actor 上绑定；Client.player 降为 legacy 镜像
                    Player.current().bindCharacter(entered);   // 跨 actor 写：跑在 actor 上（doc/12 权责语义）
                    // 收包插座接线：角色把组件接插到 actor 的 Handler 槽位（on strand 写；
                    // 角色内部组成不外泄，接线知识在 Character.bindClientHandlers）
                    entered.bindClientHandlers(Player.current().clientEventHandlers());
                    Player.current().enterWorld(firstEntry);
                } finally {
                    c.releaseClient();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            c.releaseClient();
        }
    }

}
