package org.gms.remote.gms083.server.translators;

import org.gms.client.keybind.KeyBinding;
import org.gms.remote.gms083.server.packets.AutoHpPotPacket;
import org.gms.remote.gms083.server.packets.AutoMpPotPacket;
import org.gms.remote.gms083.server.packets.KeymapPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 键位域翻译：KeyBinding 表 → KEYMAP packet + 自动用药绑定（AUTO_HP_POT/AUTO_MP_POT，
 * 键位表 91/92 槽的派生视图）。空槽归零（type 0 / action 0），键码不入 wire
 * （packet 槽位为隐含索引）。
 */
public final class KeymapTranslator {

    /** 自动用药的键位槽（客户端自动用药 UI 固定绑定 91 = HP、92 = MP） */
    private static final int AUTO_HP_SLOT = 91;
    private static final int AUTO_MP_SLOT = 92;

    private KeymapTranslator() {
    }

    public static KeymapPacket keymap(Map<Integer, KeyBinding> keymap) {
        List<KeymapPacket.Binding> out = new ArrayList<>(KeymapPacket.SLOT_COUNT);
        for (int key = 0; key < KeymapPacket.SLOT_COUNT; key++) {
            KeyBinding b = keymap.get(key);
            out.add(b != null ? new KeymapPacket.Binding((byte) b.getType(), b.getAction())
                    : new KeymapPacket.Binding((byte) 0, 0));
        }
        return new KeymapPacket(List.copyOf(out));
    }

    public static AutoHpPotPacket autoHpPot(Map<Integer, KeyBinding> keymap) {
        return new AutoHpPotPacket(potItemId(keymap, AUTO_HP_SLOT));
    }

    public static AutoMpPotPacket autoMpPot(Map<Integer, KeyBinding> keymap) {
        return new AutoMpPotPacket(potItemId(keymap, AUTO_MP_SLOT));
    }

    /** 绑定槽的 action 即药水 itemId；未绑定 → 0 */
    private static int potItemId(Map<Integer, KeyBinding> keymap, int slot) {
        KeyBinding b = keymap.get(slot);
        return b != null ? b.getAction() : 0;
    }
}
