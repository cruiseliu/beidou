package org.gms.remote;

/** 语义模块：域归属见类型注释；wire 组装归后端私有（多对多映射见 gms-server/doc/package-client.md §3）。 */
/** 面板属性域：力敏智运、max hp/mp、p/m atk（P/M_ATK 无 wire 位时实现层丢弃）。 */
public interface StatsModule {
    /** 面板属性 + hp/mp/ap 通知 */
    void updateStats(StatsUpdate update);
}
