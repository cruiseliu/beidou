package org.gms.remote.gms083.server.translators;

import org.gms.client.SkillMacro;
import org.gms.remote.gms083.server.packets.MacrosPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能宏域翻译：SkillMacro 表（固定 5 位）→ MACRO_SYS_DATA_INIT packet。
 * 空位不写——count 只计非空宏。
 */
public final class MacrosTranslator {

    private MacrosTranslator() {
    }

    public static MacrosPacket macros(SkillMacro[] macros) {
        List<MacrosPacket.Macro> out = new ArrayList<>(macros.length);
        for (SkillMacro macro : macros) {
            if (macro != null) {
                out.add(new MacrosPacket.Macro(macro.getName(), (byte) macro.getShout(),
                        macro.getSkill1(), macro.getSkill2(), macro.getSkill3()));
            }
        }
        return new MacrosPacket(List.copyOf(out));
    }
}
