package org.gms.remote.modules.basic.server;

import org.gms.client.SkillMacro;
import org.gms.remote.ServerEvent;

/**
 * 技能宏表重推（MACRO_SYS_DATA_INIT 语义）：SP 重置等运行期变更后的客户端视图刷新。
 * 入域即浅冻结（数组克隆为位置快照——元素只被整体替换，不原地变更）。
 */
public record MacrosEvent(SkillMacro[] macros) implements ServerEvent {

    public MacrosEvent {
        macros = macros.clone();
    }
}
