package org.gms.remote;

import java.util.ArrayList;
import java.util.List;

import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.modules.cooldown.CooldownModule;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.modules.skills.SkillsModule;
import org.gms.remote.modules.stats.StatsModule;

/**
 * 版本无关的远端客户端对象（隔离层门面）：游戏逻辑按真实语义调用它。
 * 每连接一个实例，经 {@code Client.getRemote()} 取用；{@code Character.remote()} 提供便捷转发。
 *
 * <p>本接口只见语义模块分组，不见具体方法——防上帝接口；各域的全部语义调用
 * 归属 {@link StatsModule}/{@link SkillsModule}/{@link BasicModule}/
 * {@link CooldownModule}/{@link InventoryModule}/{@link PetModule}。
 * 设计原则（两层/事务/多对多映射）见 gms-server/doc/package-client.md。
 *
 * <p><b>机器职责</b>（本抽象类承载）：合并域状态机（batch/batchBegin/batchEnd/batchCancel）
 * 与事件入域（dispatch）。交付（deliver）与冲刷（flushAll）为版本钩子：deliver 按日志
 * 条目的 owner 路由器直达（本类不出现任何具体事件类型）；flushAll 的顺序是版本业务序，
 * 由版本显式书写。
 */
public abstract class RemoteClient {
    /** 无连接/已断开时的空实现——对齐 Character.sendPacket 对 client==null 的静默容忍。 */
    public static RemoteClient DISCONNECTED = DummyClient.INSTANCE;

    protected final List<EventLog> logStack = new ArrayList<>();

    // ── 版本钩子 ──

    /** 冲刷全部模块路由器（固定业务序，版本显式书写） */
    protected abstract void flushAll();

    // ── 模块访问器（语义模块分组）──

    public abstract BasicModule basic();

    public abstract StatsModule stats();

    public abstract SkillsModule skills();

    public abstract CooldownModule cooldown();

    public abstract InventoryModule inventory();

    public abstract PetModule pet();

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
