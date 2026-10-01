package org.gms.remote;

/**
 * 语义模块根类（7 域模块基类的公共父级）：只承载「API call → ServerEvent」的管道契约——
 * post（freeze 门 → emit）+ emit 抽象（接事务机器）。不处理任何具体事件类型（禁止超级 class）：
 * 事件到 wire 的翻译归各域版本 router 的 deliver。
 *
 * <p><b>冻结机制（统一门）</b>：{@link #freeze} 是全部事件入域前的唯一拦截点，默认恒等——
 * 语义层不枚举、不决策任何单个事件的冻结策略。冻结时点 = 事件构造时点（标量即时抽取、
 * 集合由 record compact constructor 拷贝）；携带活引用的事件（如 InitializeEvent）默认
 * 不完整冻结，版本按域统一重载 freeze 做快照/替换（产物为版本冻结事件，ServerEventBase 宽类型，
 * FrozenInventoryEvent / FrozenInitializeEvent 先例）。
 */
public abstract class AbstractModule {

    /** 唯一出口：子类接事务机器（版本 router 一行 schedule；断连/测试实现静默抛弃） */
    protected abstract void emit(ServerEventBase event);

    /** 通用冻结门（见类注）：默认恒等，版本按域统一重载 */
    protected ServerEventBase freeze(ServerEvent event) {
        return event;
    }

    /** API 方法专用出口：一律经此（freeze → emit），不得直呼 emit */
    protected final void post(ServerEvent event) {
        emit(freeze(event));
    }
}
