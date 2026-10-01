package org.gms.remote.gms083.server.translators;

import org.gms.client.keybind.QuickslotBinding;
import org.gms.remote.gms083.server.packets.QuickslotPacket;

/**
 * 快捷栏域翻译：QuickslotBinding → QUICKSLOT_INIT packet。
 * null 绑定 = 从未自定义 → 客户端默认表（custom 布尔由此固化：默认表 = false，
 * 客户端跳过解析走 CQuickslotKeyMappedMan 默认键位）。
 */
public final class QuickslotTranslator {

    private QuickslotTranslator() {
    }

    public static QuickslotPacket quickslot(QuickslotBinding binding) {
        byte[] keys = binding != null ? binding.GetKeybindings() : QuickslotBinding.DEFAULT_QUICKSLOTS;
        boolean custom = !java.util.Arrays.equals(keys, QuickslotBinding.DEFAULT_QUICKSLOTS);
        return new QuickslotPacket(custom, keys);
    }
}
