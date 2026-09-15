package org.gms.remote.modules.basic;

import org.gms.client.character.Character;
import org.gms.remote.modules.basic.server.BasicUpdate;

/** 语义模块：域归属见类型注释；wire 组装归后端私有（多对多映射见 gms-server/doc/package-client.md §3）。 */
/** 基础标识域：jobId/level/exp 等"搭车"字段 + 动作锁（用户决策：unlockActions 归此模块，
 *  v83 编码并入 STAT_CHANGED 首字节/空包）。 */
public interface BasicModule {
    void updateBasic(BasicUpdate update);

    void unlockActions();

    /**
     * 进图客户端视图初始化（SET_FIELD 主包 + 键位表/快捷栏/技能宏/自动用药绑定，
     * doc/12 §21 追记 5）。事件（InitializeEvent）与 wire 派生归版本实现，不对
     * gameplay 暴露；进不进合并域由 caller 的 update 域决定。
     */
    void initialize(Character chr);

    /** 技能宏表重推（SP 重置清引用等运行期变更；与入图初始化同一 wire 包） */
    void updateMacros(org.gms.client.SkillMacro[] macros);
}
