package org.gms.remote;

import org.gms.client.Client;
import org.gms.net.PacketHandler;
import org.gms.net.server.coordinator.session.PlayerSession;

import java.util.ArrayList;
import java.util.List;


/**
 * 远端客户端基类（域无关基础设施）：合并域状态机（batch 系列）+ 事件入域 + C→S 分派。
 * 域能力以 interface 视图交付——{@link RemoteClient}（世界域模块面）/ LoginRemoteClient
 * （跳板域面，随相 C 演进）；实现类同时实现两者，外部只以单一 interface 类型拿到实例
 * （doc/12 跳板/世界分域）。获取纪律：世界域代码经 {@code Player.remote()}（strand 抽象），
 * Client 不在公共面暴露本概念。
 *
 * <p>交付（deliver）与冲刷（flushAll）为版本钩子：deliver 按日志条目的 owner 路由器直达
 * （本类不出现任何具体事件类型）；flushAll 的顺序是版本业务序，由版本显式书写。
 */
public abstract class RemoteClientBase {
    /** 无连接/已断开时的空实现——对齐 Character.sendPacket 对 client==null 的静默容忍。 */
    public static RemoteClientBase DISCONNECTED = DummyClient.INSTANCE;

    protected final List<EventLog> logStack = new ArrayList<>();

    // ── 版本钩子 ──

    /** 冲刷全部模块路由器（固定业务序，版本显式书写） */
    protected abstract void flushAll();

    /**
     * 世界入口初始化协议（PLAYER_LOGGEDIN，doc/12 §21）：连接初始化不属于任何语义
     * 模块，直接以基类 final 模板承载（不走 pipeline/槽位）。全部分 delegate 到
     * {@code PlayerSession.bindClient}——会话建立（strand 诞生/换绑）+ 入场编舞。
     * 由分发壳（Gms083.resolveHandler 的特判，queued 裸 strand）调用；跳板域连接的
     * 分发表无此 op，天然不可达。实现类须 implements RemoteClient（域视图契约）。
     */
    public final void clientInit(int characterId, Client legacyClient) {
        PlayerSession.bindClient(characterId, (RemoteClient) this, legacyClient);
    }

    /**
     * C→S 分派（opcode → 本域 handler；表外 op 返回 null，由连接管道丢弃）。
     * 分域靠装配构造性保证：登录端口连接装配 LoginRemoteClient、世界端口装配 gms083，
     * 一个连接只有一个语义层实例（doc/12 跳板/世界分域）。
     */
    public abstract PacketHandler resolveHandler(short opcode);

    // ── 合并域机器 ──

    /**
     * 事件入域：无开域 → owner 交付并冲刷（立即路径）；开域中 → 入当前段（commit 时按
     * 原始发生序回放到各 owner）。owner 由产出事件的 route 自声明（this）。
     */
    public void schedule(ServerEventDest dest, ServerEventBase event) {
        if (logStack.isEmpty()) {
            dest.deliver(event);
            flushAll();
        } else {
            logStack.getLast().add(dest, event);
        }
    }

    /** 开启合并域：try-with-resources 使用，close 即统一发送。嵌套开启返回空收口语义（外层负责）。 */
    public RemoteClientBatch batch() {
        batchBegin();
        return new RemoteClientBatch(this, logStack.getLast());
    }

    public void batchBegin() {
        logStack.add(new EventLog());
    }

    /** 收口：最外层段按原始发生序回放到各 owner，随后统一冲刷；嵌套段并入父段末尾 */
    public void batchEnd() {
        EventLog log = logStack.removeLast();
        if (logStack.isEmpty()) {
            log.entries().forEach(entry -> entry.dest().deliver(entry.event()));
            flushAll();
        } else {
            logStack.getLast().addAll(log);
        }
    }

    /** 丢弃当前段（不回放、不冲刷） */
    public void batchCancel() {
        logStack.removeLast();
    }

    public void batchEnd(EventLog log) {
        if (logStack.isEmpty() || logStack.getLast() != log) {
            throw new RuntimeException("RemoteClient: wrong batch order (commit)");
        }
        batchEnd();
    }

    public void batchCancel(EventLog log) {
        if (logStack.isEmpty() || logStack.getLast() != log) {
            throw new RuntimeException("RemoteClient: wrong batch order (discard)");
        }
        batchCancel();
    }
}
