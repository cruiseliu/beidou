package org.gms.remote;

/** 语义模块：域归属见类型注释；wire 组装归后端私有（多对多映射见 gms-server/doc/package-client.md §3）。 */
/** 基础标识域：jobId/level/exp 等"搭车"字段 + 动作锁（用户决策：unlockActions 归此模块，
 *  v83 编码并入 STAT_CHANGED 首字节/空包）。 */
public interface BasicModule {
    void updateBasic(BasicUpdate update);

    void unlockActions();
}
