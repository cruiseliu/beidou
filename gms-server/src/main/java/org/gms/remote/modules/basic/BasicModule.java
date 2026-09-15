package org.gms.remote.modules.basic;

import org.gms.client.character.Character;

/** 语义模块：域归属见类型注释；wire 组装归后端私有（多对多映射见 gms-server/doc/package-client.md §3）。 */
/** 基础标识域：jobId/level/exp 等"搭车"字段 + 动作锁（用户决策：unlockActions 归此模块，
 *  v83 编码并入 STAT_CHANGED 首字节/空包）。同段多次提交同字段后写覆盖（§2）。
 */
public interface BasicModule {
    /**
     * Send all data.
     */
    void initialize(Character chr);

    /** jobId 变更（转职）。 */
    void updateJob(int jobId);

    /** level 变更。 */
    void updateLevel(int level);

    /** exp 变更。 */
    void updateExp(long exp);

    void unlockActions();
}
