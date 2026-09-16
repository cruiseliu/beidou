package org.gms.remote.modules.stats;

import org.gms.remote.AbstractModule;
import org.gms.remote.modules.stats.server.StatsEvent;
import org.gms.remote.modules.stats.server.StatsUpdate;

/** 面板属性域（语义基类）：力敏智运、max hp/mp、p/m atk（P/M_ATK 无 wire 位时实现层丢弃）。 */
public abstract class StatsModule extends AbstractModule {

    /** 面板属性 + hp/mp/ap 通知 */
    public final void updateStats(StatsUpdate update) {
        post(new StatsEvent(update));
    }
}
