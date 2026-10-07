package org.gms.infra;

import java.util.EnumSet;

/**
 * strict 管线因果上下文（迁移期临时机制）：期望 owner（type+id）+ 窗口种类，随任务传播。
 *
 * <p>机制（tracing/MDC 同款）：开窗方构造并 {@link #establish}；post/run/supply 入队时
 * {@link #current()} 捕获随 Task 冻结，executor 在任务体执行期重建视图，body 结束清除。
 * 守卫经 {@link #current()} 静态读 + 字段直比——Task 对象对守卫不可见，ThreadLocal 是
 * 唯一执行期桥（与 Strand.CURRENT / ActorShim.CURRENT 同构）。null = 无窗流量（零包装）。
 */
public final class PipelineContext {

    private static final ThreadLocal<PipelineContext> CURRENT = new ThreadLocal<>();

    public enum OwnerType {
        CHARACTER, MAP
    }

    /** 期望 actor 的 owner 标识（type + id），executor 在 establish 时盖章 */
    public record Owner(OwnerType type, int id) {
    }

    /** 期望 owner（"actor 与 owner 是否一致"的 owner 侧） */
    public OwnerType ownerType;
    public int ownerId;
    /** 在窗种类 */
    public EnumSet<StrictWindow> kinds;
    /** 窗口模式（LOG = 违规记日志放行；ASSERT = 违规抛出）。窗口内种类共用。 */
    public StrictWindow.Mode mode;

    public PipelineContext(OwnerType ownerType, int ownerId, EnumSet<StrictWindow> kinds,
                           StrictWindow.Mode mode) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.kinds = kinds;
        this.mode = mode;
    }

    /** 当前因果上下文（无则 null）：post 捕获点与守卫共用 */
    public static PipelineContext current() {
        return CURRENT.get();
    }

    /** 开窗方 / executor 建立任务体执行期视图 */
    public static void establish(PipelineContext ctx) {
        CURRENT.set(ctx);
    }

    /** 清除（任务体收尾 / 显式截断点） */
    public static void clear() {
        CURRENT.remove();
    }
}
