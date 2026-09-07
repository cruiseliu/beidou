package org.gms.remote.v83.out.route;

import org.gms.remote.BasicModule;
import org.gms.remote.BasicUpdate;
import org.gms.remote.out.events.BasicEvent;
import org.gms.remote.out.events.SemanticEvent;
import org.gms.remote.out.events.UnlockActionsEvent;

import java.util.function.Consumer;

/** 基础标识域 route：模块调用 → 事件入域（unlockActions 归此模块，v83 编码并入 STAT_CHANGED）。 */
public final class BasicRoute implements BasicModule {

    private final Consumer<SemanticEvent> dispatch;

    public BasicRoute(Consumer<SemanticEvent> dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public void updateBasic(BasicUpdate update) {
        dispatch.accept(new BasicEvent(update));
    }

    @Override
    public void unlockActions() {
        dispatch.accept(new UnlockActionsEvent());
    }
}
