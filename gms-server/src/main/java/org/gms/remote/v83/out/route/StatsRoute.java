package org.gms.remote.v83.out.route;

import org.gms.remote.StatsModule;
import org.gms.remote.StatsUpdate;
import org.gms.remote.out.events.SemanticEvent;
import org.gms.remote.out.events.StatsEvent;

import java.util.function.Consumer;

/**
 * stats 域 route：模块调用 → 事件入域。事件 sink 经门面构造注入（版本实现内部协作；
 * 事件去向由作用域状态决定，route 不感知）。
 */
public final class StatsRoute implements StatsModule {

    private final Consumer<SemanticEvent> dispatch;

    public StatsRoute(Consumer<SemanticEvent> dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public void updateStats(StatsUpdate update) {
        dispatch.accept(new StatsEvent(update));
    }
}
